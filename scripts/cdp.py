#!/usr/bin/env python3
"""Drive headless Chrome over the DevTools Protocol (no third-party deps).

usage: cdp.py <url> [--until-js EXPR] [--timeout SEC] [--screenshot out.png] [--eval EXPR]...

Streams console output with real (wall-clock) timestamps, waits until EXPR is truthy in the
page, optionally evaluates expressions and saves a screenshot. Exit code 0 if the condition
was met, 1 on timeout.
"""
import argparse
import base64
import json
import os
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
import zlib

CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")


class WS:
    def __init__(self, url):
        assert url.startswith("ws://")
        host_port, path = url[5:].split("/", 1)
        host, port = host_port.split(":")
        self.sock = socket.create_connection((host, int(port)))
        key = base64.b64encode(os.urandom(16)).decode()
        req = (f"GET /{path} HTTP/1.1\r\nHost: {host_port}\r\nUpgrade: websocket\r\n"
               f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
        self.sock.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.sock.recv(4096)
        self.rest = buf.split(b"\r\n\r\n", 1)[1]
        self.next_id = 0

    def _recv_exact(self, n):
        while len(self.rest) < n:
            chunk = self.sock.recv(1 << 20)
            if not chunk:
                raise EOFError
            self.rest += chunk
        out, self.rest = self.rest[:n], self.rest[n:]
        return out

    def send(self, obj):
        data = json.dumps(obj).encode()
        hdr = bytearray([0x81])
        n = len(data)
        if n < 126:
            hdr.append(0x80 | n)
        elif n < 65536:
            hdr.append(0x80 | 126)
            hdr += struct.pack(">H", n)
        else:
            hdr.append(0x80 | 127)
            hdr += struct.pack(">Q", n)
        mask = os.urandom(4)
        hdr += mask
        self.sock.sendall(bytes(hdr) + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def recv(self, timeout=None):
        self.sock.settimeout(timeout)
        try:
            payload = b""
            while True:
                b1, b2 = self._recv_exact(2)
                n = b2 & 0x7F
                if n == 126:
                    n = struct.unpack(">H", self._recv_exact(2))[0]
                elif n == 127:
                    n = struct.unpack(">Q", self._recv_exact(8))[0]
                payload += self._recv_exact(n)
                if b1 & 0x80:
                    break
            return json.loads(payload)
        except socket.timeout:
            return None
        finally:
            self.sock.settimeout(None)

    def call(self, method, params=None, pump=None, timeout_sec=10):
        self.next_id += 1
        mid = self.next_id
        self.send({"id": mid, "method": method, "params": params or {}})
        # A socket timeout only bounds each individual read. A steady stream of
        # console/heartbeat events would otherwise keep resetting it and let a
        # command that never replies block the whole run forever.
        deadline = time.time() + timeout_sec
        while True:
            remaining = deadline - time.time()
            if remaining <= 0:
                raise TimeoutError(method)
            msg = self.recv(remaining)
            if msg is None:
                raise TimeoutError(method)
            if msg.get("id") == mid:
                return msg
            if pump:
                pump(msg)


def png_has_visible_pixels(data):
    """True when a PNG contains at least one non-transparent pixel.

    Page.captureScreenshot returns a fully transparent image when the only
    visible content lives in the Java iframe (Chrome's software compositor does
    not include that frame in this configuration). Decode cheaply enough to
    tell the difference instead of silently shipping an empty artifact.
    """
    try:
        if not data or data[:8] != b"\x89PNG\r\n\x1a\n":
            return bool(data)
        pos = 8
        width = height = bit_depth = color_type = 0
        idat = bytearray()
        while pos + 8 <= len(data):
            length = struct.unpack(">I", data[pos:pos + 4])[0]
            ctype = data[pos + 4:pos + 8]
            body = data[pos + 8:pos + 8 + length]
            pos += 12 + length
            if ctype == b"IHDR":
                width, height, bit_depth, color_type = struct.unpack(">IIBB", body[:10])
            elif ctype == b"IDAT":
                idat += body
            elif ctype == b"IEND":
                break
        # Unknown layouts are treated as visible so a real capture is never
        # discarded just because it cannot be decoded here.
        if not width or not height or bit_depth != 8 or color_type not in (4, 6):
            return True
        channels = 4 if color_type == 6 else 2
        stride = width * channels
        raw = zlib.decompress(bytes(idat))
        if len(raw) < height * (stride + 1):
            return True
        prev = bytearray(stride)
        p = 0
        for _ in range(height):
            filt = raw[p]
            p += 1
            line = bytearray(raw[p:p + stride])
            p += stride
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                if filt == 1:
                    line[i] = (line[i] + a) & 255
                elif filt == 2:
                    line[i] = (line[i] + b) & 255
                elif filt == 3:
                    line[i] = (line[i] + ((a + b) >> 1)) & 255
                elif filt == 4:
                    pa = abs(b - c)
                    pb = abs(a - c)
                    pc = abs(a + b - 2 * c)
                    pred = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                    line[i] = (line[i] + pred) & 255
            for i in range(channels - 1, stride, channels):
                if line[i] > 8:
                    return True
            prev = line
        return False
    except Exception:
        return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--until-js", default="window.__mc && /complete|failed/.test(window.__mc.stage)")
    ap.add_argument("--timeout", type=float, default=600)
    ap.add_argument("--screenshot")
    ap.add_argument("--eval", action="append", default=[])
    ap.add_argument("--preload-js", help="JavaScript evaluated in every new document before page scripts")
    ap.add_argument("--assert-js", action="append", default=[], help="JavaScript expression that must be truthy after the wait condition")
    ap.add_argument("--viewport", help="emulate a CSS viewport, e.g. 414x896")
    ap.add_argument("--dpr", type=float, default=2.0, help="device scale factor for --viewport")
    ap.add_argument("--gpu", action="store_true", help="use the real GPU instead of SwiftShader")
    ap.add_argument("--quiet", action="store_true", help="do not stream console output")
    ap.add_argument("--state-every", type=float, default=15.0,
                    help="log a compact page/Java state snapshot every N seconds (0 disables)")
    ap.add_argument("--actions", default="",
                    help="semicolon-separated UI actions executed before the wait condition: "
                         "click=Label[@ScreenSubstring]; cycle=LabelPrefix|TargetSubstring; shot=path.png")
    ap.add_argument("--actions-timeout", type=float, default=1500.0,
                    help="maximum seconds to finish all --actions (default 1500)")
    ap.add_argument("--action-settle", type=float, default=0.8,
                    help="seconds to wait after a click before the next action (default 0.8)")
    ap.add_argument("--soak-seconds", type=float, default=0.0,
                    help="after the wait condition and assertions, keep the page alive for N seconds "
                         "while sampling FPS and memory to catch Chrome 'Aw Snap' renderer crashes")
    ap.add_argument("--fps-window", type=float, default=5.0,
                    help="seconds per FPS sample during --soak-seconds (default 5)")
    ap.add_argument("--min-fps", type=float, default=0.0,
                    help="fail if every soak FPS sample is below this value (0 disables; use on real GPUs)")
    ap.add_argument("--soak-screenshot", default="build/soak_end.png",
                    help="screenshot captured at the end of the soak (default build/soak_end.png)")
    a = ap.parse_args()

    actions = []
    for raw in (a.actions or "").split(";"):
        raw = raw.strip()
        if not raw:
            continue
        kind, _, value = raw.partition("=")
        kind = kind.strip().lower()
        if kind in ("click", "cycle", "shot", "wait"):
            actions.append({"kind": kind, "value": value.strip(), "clicks": 0, "done": False,
                            "last_raw": "", "next_click_at": 0.0})
        else:
            raise SystemExit("unknown --actions step: " + raw)

    profile = tempfile.mkdtemp()
    port = 9300 + os.getpid() % 500
    headless = os.environ.get("CHROME_HEADLESS", "1") != "0"
    args = [CHROME]
    if headless:
        args.append("--headless=new")
    args += [f"--remote-debugging-port={port}", f"--user-data-dir={profile}",
             "--no-proxy-server",
             "--no-first-run", "--window-size=1280,720", "--enable-unsafe-webgpu", "about:blank"]
    # Chrome refuses to start as root with its sandbox enabled. GitHub's runner
    # is non-root, so this only affects local/container verification.
    if hasattr(os, "geteuid") and os.geteuid() == 0:
        args.insert(1, "--no-sandbox")
    if not a.gpu:
        # GitHub-hosted Linux runners have no physical GPU; explicitly select
        # SwiftShader's Vulkan backend for WebGPU as well as ANGLE for WebGL.
        args[1:1] = [
            "--enable-features=Vulkan",
            "--use-vulkan=swiftshader",
            "--disable-vulkan-surface",
            "--use-angle=swiftshader",
        ]
    extra_flags = os.environ.get("CHROME_FLAGS", "").split()
    if extra_flags:
        args[1:1] = extra_flags
    chrome = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    ok = False
    crashed = {"flag": False, "reason": ""}
    browser_errors = []
    browser_console = []
    os.makedirs("build", exist_ok=True)
    # Keep both diagnostics files open in append mode. CI can kill the job at
    # any moment; rewriting the whole log on every console event used to leave
    # an empty file behind when the process died mid-write.
    console_file = open(os.path.join("build", "browser_console.log"), "w", encoding="utf-8", buffering=1)
    error_file = open(os.path.join("build", "browser_error.txt"), "w", encoding="utf-8", buffering=1)
    error_count = 0

    def record(line):
        browser_console.append(line)
        # A long session emits tens of thousands of console lines. Keep the
        # in-memory copy bounded; the file on disk remains complete.
        if len(browser_console) > 20000:
            del browser_console[:10000]
        console_file.write(line + "\n")
        console_file.flush()

    def flush_diagnostics():
        try:
            console_file.flush()
            error_file.flush()
        except Exception:
            pass

    def handle_cancel(signum, frame):
        flush_diagnostics()
        os._exit(128 + signum)

    signal.signal(signal.SIGTERM, handle_cancel)
    signal.signal(signal.SIGINT, handle_cancel)

    try:
        page = None
        for _ in range(600):
            try:
                tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json"))
                page = next(t for t in tabs if t["type"] == "page")
                break
            except Exception:
                time.sleep(0.1)
        if page is None:
            raise SystemExit("Chrome did not start (DevTools endpoint unreachable)")
        ws = WS(page["webSocketDebuggerUrl"])
        t0 = time.time()

        def save_browser_error(message):
            nonlocal error_count
            message = str(message).strip()[:12000]
            if message and message not in browser_errors:
                browser_errors.append(message)
                error_count += 1
                error_file.write(message + "\n\n")
                error_file.flush()

        def pump(msg):
            m = msg.get("method")
            if m == "Inspector.targetCrashed":
                crashed["flag"] = True
                crashed["reason"] = "Chrome renderer crashed (Inspector.targetCrashed) - Aw Snap"
                save_browser_error(crashed["reason"])
                record("{:7.2f}s CRASH: {}".format(time.time() - t0, crashed["reason"]))
                if not a.quiet:
                    print("CRASH: " + crashed["reason"], flush=True)
                return
            if m == "Inspector.detached":
                reason = str(msg.get("params", {}).get("reason", ""))
                if reason and reason != "target_closed":
                    crashed["flag"] = True
                    crashed["reason"] = "Chrome detached the page: " + reason
                    save_browser_error(crashed["reason"])
                    record("{:7.2f}s CRASH: {}".format(time.time() - t0, crashed["reason"]))
                    if not a.quiet:
                        print("CRASH: " + crashed["reason"], flush=True)
                return
            if m == "Runtime.consoleAPICalled":
                params = msg["params"]
                message = " ".join(str(x.get("value", x.get("description", ""))) for x in params["args"])
                if params.get("type") == "error" or any(token in message.lower() for token in ("webassembly.compile", "compileerror", "br_on_cast", "global.set")):
                    save_browser_error("console." + params.get("type", "log") + ": " + message)
                console_line = "{:7.2f}s console.{}: {}".format(time.time() - t0, params["type"], message)
                record(console_line)
                if not a.quiet:
                    print(console_line[:12000], flush=True)
            elif m == "Runtime.exceptionThrown":
                d = msg["params"]["exceptionDetails"]
                detail = d.get("exception", {}).get("description", d.get("text", "Unknown page exception"))
                save_browser_error("Uncaught browser exception: " + detail)
                exception_line = "{:7.2f}s EXCEPTION: {}".format(time.time() - t0, detail)
                record(exception_line)
                if not a.quiet:
                    print(exception_line[:12000], flush=True)
            elif m == "Network.responseReceived":
                response = msg.get("params", {}).get("response", {})
                status = int(response.get("status", 0) or 0)
                if status >= 400:
                    detail = "HTTP {} {} ({})".format(status, response.get("url", ""), response.get("statusText", ""))
                    failed_requests.append(detail)
                    record("{:7.2f}s NETWORK: {}".format(time.time() - t0, detail))
                    save_browser_error(detail)
            elif m == "Network.loadingFailed":
                params = msg.get("params", {})
                detail = "Network load failed: {} ({})".format(params.get("errorText", ""), params.get("requestId", ""))
                failed_requests.append(detail)
                record("{:7.2f}s NETWORK: {}".format(time.time() - t0, detail))
                save_browser_error(detail)
            elif m == "Log.entryAdded":
                entry = msg.get("params", {}).get("entry", {})
                message = entry.get("text", "")
                record("{:7.2f}s chrome.{}: {}".format(
                    time.time() - t0, entry.get("level", "log"), message))
                if entry.get("level") == "error" or any(token in message.lower() for token in ("webassembly.compile", "compileerror", "br_on_cast", "global.set")):
                    save_browser_error("Chrome log: " + message)
            flush_diagnostics()

        failed_requests = []
        ws.call("Runtime.enable", pump=pump)
        ws.call("Page.enable", pump=pump)
        ws.call("Inspector.enable", pump=pump)
        ws.call("Log.enable", pump=pump)
        ws.call("Network.enable", pump=pump)
        # This timer is deliberately installed before page scripts. Its console events
        # are delivered by CDP without Runtime.evaluate polling: if the renderer's main
        # thread is monopolized, heartbeat ticks stop; when it yields, queued ticks resume.
        heartbeat_js = """(() => {
            // addScriptToEvaluateOnNewDocument runs in every frame. Only the top-level
            // page should emit diagnostics; otherwise iframe churn floods CDP/logs.
            if (window !== window.top || window.__cdpHeartbeatInstalled) return;
            window.__cdpHeartbeatInstalled = true;
            const started = Date.now();
            let tick = 0;
            console.log("[CDP_HEARTBEAT] installed");
            const timer = setInterval(() => {
                console.log("[CDP_HEARTBEAT] tick=" + (++tick) +
                    " elapsed_ms=" + (Date.now() - started));
                if (tick >= 90) clearInterval(timer);
            }, 2000);
        })();"""
        ws.call("Page.addScriptToEvaluateOnNewDocument", {"source": heartbeat_js}, pump=pump)
        if a.preload_js:
            ws.call("Page.addScriptToEvaluateOnNewDocument", {"source": a.preload_js}, pump=pump)
        if a.viewport:
            width, height = (int(part) for part in a.viewport.lower().split("x", 1))
            ws.call("Emulation.setDeviceMetricsOverride", {
                "width": width,
                "height": height,
                "deviceScaleFactor": a.dpr,
                "mobile": True,
            }, pump=pump)
        ws.call("Page.navigate", {"url": a.url}, pump=pump)
        fail_js = """(() => {
            const top = window.__mc || {};
            const bridge = (window.mcBridge && window.mcBridge.state) || {};
            const frame = document.getElementById('mc-brain');
            const brain = (frame && frame.contentWindow && frame.contentWindow.__mc) || {};
            return top.stage === 'failed' || !!top.error || !!brain.error || !!bridge.error
                || !!window.__mcBodyError;
        })()"""
        state_js = """(() => {
            const f = document.getElementById('mc-brain');
            const w = f && f.contentWindow;
            const b = (w && w.__mc) || {};
            const top = window.__mc || {};
            const bridge = (window.mcBridge && window.mcBridge.state) || {};
            const canvas = w && w.__mcSDL && w.__mcSDL.canvas;
            const gpu = w && w.__mcWebGPU;
            const frames = (w && w.__mcFrames) || {};
            let bodySections = -1;
            try { if (bridge.module && bridge.module.java_section_count) bodySections = Number(bridge.module.java_section_count()) || 0; } catch (e) {}
            const st = gpu && gpu.stats;
            return JSON.stringify({t:Math.round(performance.now()),stage:top.stage||'',javaStage:top.javaStage||b.javaStage||'',iframeStage:b.stage||'',boot:b['boot-state']||'',buttons:b['screen-buttons']||'',crash:b['crash-report']||'',error:b.error||bridge.error||'',bodyReady:!!window.__mcBodyReady,frame:Number(gpu&&gpu.frame)||0,fps:Number(frames.fps)||0,workMs:Number((frames.workAvg||0).toFixed(2)),canvas:canvas?canvas.width+'x'+canvas.height:'',sections:bridge.sections||0,bodySections,draws:st?st.draws:-1,passes:st?st.passes:-1,bindGroups:st?st.bindGroups:-1,pipelines:st?st.pipelines:-1,liveBuffers:st?st.liveBuffers:-1,liveTextures:st?st.liveTextures:-1,liveViews:st?st.liveViews:-1,livePipelines:st?st.livePipelines:-1,planTotal:gpu?gpu.planTotal:-1,objSlots:gpu?gpu.obj.length:-1,transientMB:st?Math.round(st.transientBytes/1048576):-1,transientChunks:st?st.transientChunks:-1,heapMB:performance.memory?Math.round(performance.memory.usedJSHeapSize/1048576):-1,lost:gpu&&gpu.lost?String(gpu.lost.message||'lost'):'',queue:w&&w.__mcSDL&&w.__mcSDL.queue?w.__mcSDL.queue.length:-1});
        })()"""
        find_button_js = """(() => {
            const f = document.getElementById('mc-brain');
            const w = f && f.contentWindow;
            const b = (w && w.__mc) || {};
            const raw = String(b['screen-buttons'] || '');
            if (!raw) return null;
            const spec = %s;
            let label = spec, screen = '';
            const at = spec.indexOf('@');
            if (at >= 0) { label = spec.slice(0, at); screen = spec.slice(at + 1); }
            const head = raw.split(' | ')[0];
            const sm = /screen=([^\\s]+)/.exec(head);
            if (screen && !(sm && sm[1].indexOf(screen) >= 0)) return null;
            const winM = / window=(\\d+)x(\\d+)/.exec(head);
            const guiM = / gui=(\\d+)x(\\d+)/.exec(head);
            if (!winM || !guiM) return null;
            const W = +winM[1], H = +winM[2], GW = +guiM[1], GH = +guiM[2];
            const prefix = label.endsWith('*');
            const want = prefix ? label.slice(0, -1) : label;
            for (const part of raw.split(' | ').slice(1)) {
                const i = part.lastIndexOf('@');
                if (i < 0) continue;
                const name = part.slice(0, i);
                if (prefix ? !name.startsWith(want) : name !== want) continue;
                const nums = part.slice(i + 1).split(',').map(Number);
                if (nums.length < 4) continue;
                return { label: name, screen: sm ? sm[1] : '',
                    x: (nums[0] + nums[2] / 2) * W / GW,
                    y: (nums[1] + nums[3] / 2) * H / GH, raw };
            }
            return null;
        })()"""
        actions_done = False
        actions_failed = False
        actions_deadline = time.time() + a.actions_timeout
        action_hold_until = 0.0

        def js_value(expr, timeout_sec=20):
            r = ws.call("Runtime.evaluate", {"expression": expr, "returnByValue": True},
                        pump=pump, timeout_sec=timeout_sec)
            return r.get("result", {}).get("result", {}).get("value")

        iframe_canvas_js = """(() => {
            const f = document.getElementById('mc-brain');
            const w = f && f.contentWindow;
            if (!w) return '';
            const c = (w.__mcSDL && w.__mcSDL.canvas) || w.document.querySelector('canvas');
            if (!c || !c.width || !c.height) return '';
            try { return c.toDataURL('image/png'); } catch (e) { return ''; }
        })()"""

        def capture(path, timeout_sec=60):
            """Save the best available pixels for the current shell mode.

            In java-ui mode the top page contains only the Java iframe, and
            Page.captureScreenshot can come back fully transparent. Capture the
            Java canvas itself in that case so title/world screenshots are real
            evidence instead of empty 4 KB PNGs.
            """
            top_png = b""
            try:
                r = ws.call("Page.captureScreenshot", {"format": "png"},
                            pump=pump, timeout_sec=timeout_sec)
                top_png = base64.b64decode(r.get("result", {}).get("data") or "")
            except Exception as exc:
                print("page screenshot failed: {}: {}".format(type(exc).__name__, exc), flush=True)
            try:
                java_ui = bool(js_value(
                    "document.body.classList.contains('java-ui')", timeout_sec=10))
            except Exception:
                java_ui = False
            frame_png = b""
            try:
                url = js_value(iframe_canvas_js, timeout_sec=min(30, timeout_sec))
                if isinstance(url, str) and url.startswith("data:image/png;base64,"):
                    frame_png = base64.b64decode(url.split(",", 1)[1])
            except Exception:
                pass
            top_ok = bool(top_png) and png_has_visible_pixels(top_png)
            frame_ok = bool(frame_png) and png_has_visible_pixels(frame_png)
            if frame_ok and (java_ui or not top_ok):
                chosen, source = frame_png, "java-canvas"
            elif top_ok:
                chosen, source = top_png, "page"
            elif frame_ok:
                chosen, source = frame_png, "java-canvas"
            elif top_png:
                chosen, source = top_png, "page-blank"
            else:
                raise RuntimeError("no screenshot data")
            with open(path, "wb") as f:
                f.write(chosen)
            print("screenshot -> {} ({}, {} bytes{})".format(
                path, source, len(chosen),
                "" if (top_ok or frame_ok) else ", capture is transparent"), flush=True)
            return True

        def click_at(x, y):
            try:
                ws.call("Input.dispatchMouseEvent",
                        {"type": "mouseMoved", "x": x, "y": y}, pump=pump, timeout_sec=20)
                ws.call("Input.dispatchMouseEvent",
                        {"type": "mousePressed", "x": x, "y": y, "button": "left",
                         "buttons": 1, "clickCount": 1}, pump=pump, timeout_sec=20)
                ws.call("Input.dispatchMouseEvent",
                        {"type": "mouseReleased", "x": x, "y": y, "button": "left",
                         "buttons": 0, "clickCount": 1}, pump=pump, timeout_sec=20)
                return True
            except TimeoutError as exc:
                print("input click timed out: " + str(exc), flush=True)
                return False

        soak_js = """(() => {
            const f = document.getElementById('mc-brain');
            const w = f && f.contentWindow;
            const F = (w && w.__mcFrames) || {};
            const G = w && w.__mcWebGPU;
            const S = G && G.snapshot ? G.snapshot() : null;
            return JSON.stringify({frame: S ? S.frame : (G ? G.frame : -1),
                fps: F.fps || 0, workAvg: F.workAvg || 0, snap: S});
        })()"""

        def run_soak():
            """Keep the page running and sample the presented-frame rate.

            A Chrome renderer killed for memory exhaustion ("Aw Snap") closes the
            DevTools target, which raises EOFError/OSError here; Inspector events
            also mark it immediately. FPS is measured from __mcWebGPU.frame deltas,
            i.e. frames actually presented, not JS timer callbacks.
            """
            if a.soak_seconds <= 0:
                return True
            print("{:7.2f}s SOAK start: {:.0f}s".format(time.time() - t0, a.soak_seconds), flush=True)
            end = time.time() + a.soak_seconds
            started_at = time.time()
            last_frame = None
            last_at = 0.0
            last_sample = 0.0
            samples = []
            stalls = 0
            while time.time() < end:
                if crashed["flag"]:
                    print("SOAK CRASH: " + crashed["reason"], flush=True)
                    return False
                try:
                    msg = ws.recv(0.5)
                except (EOFError, OSError) as exc:
                    crashed["flag"] = True
                    crashed["reason"] = ("DevTools connection closed ({}): renderer likely crashed (Aw Snap)"
                                         .format(type(exc).__name__))
                    save_browser_error(crashed["reason"])
                    print("SOAK CRASH: " + crashed["reason"], flush=True)
                    return False
                if msg:
                    try:
                        pump(msg)
                    except Exception as exc:
                        print("SOAK pump error: {}: {}".format(type(exc).__name__, exc), flush=True)
                now = time.time()
                if now - last_sample < a.fps_window:
                    continue
                last_sample = now
                try:
                    value = js_value(soak_js, timeout_sec=30)
                    stalls = 0
                except TimeoutError:
                    stalls += 1
                    print("SOAK evaluate stalled ({}/3); renderer may be busy".format(stalls), flush=True)
                    if stalls < 3:
                        continue
                    crashed["flag"] = True
                    crashed["reason"] = "page stopped answering during soak (3 consecutive stalls)"
                    save_browser_error(crashed["reason"])
                    print("SOAK CRASH: " + crashed["reason"], flush=True)
                    return False
                except (EOFError, OSError) as exc:
                    crashed["flag"] = True
                    crashed["reason"] = ("page connection closed during soak ({}): renderer likely crashed"
                                         .format(type(exc).__name__))
                    save_browser_error(crashed["reason"])
                    print("SOAK CRASH: " + crashed["reason"], flush=True)
                    return False
                try:
                    data = json.loads(value) if value else {}
                except ValueError:
                    data = {}
                frame = int(data.get("frame") or 0)
                window_fps = 0.0
                if last_frame is not None and now > last_at:
                    window_fps = (frame - last_frame) / (now - last_at)
                    samples.append(window_fps)
                last_frame, last_at = frame, now
                snap = data.get("snap") or {}
                transient_mb = round((snap.get("transientBytes") or 0) / 1048576, 1)
                line = ("SOAK {:6.1f}s frame={} fps={:.1f} javaFps={:.1f} workMs={:.1f} "
                        "presentMs={:.2f} heapMB={} liveTex={} liveBuf={} livePipe={} plans={} "
                        "objSlots={} transientMB={} lost={}").format(
                            now - started_at, frame, window_fps, float(data.get("fps") or 0),
                            float(data.get("workAvg") or 0), float(snap.get("presentAvg") or 0),
                            snap.get("heapMB", -1), snap.get("liveTextures", -1),
                            snap.get("liveBuffers", -1), snap.get("livePipelines", -1),
                            snap.get("planTotal", -1), snap.get("objSlots", -1),
                            transient_mb, snap.get("lost") or "")
                record(line)
                print(line, flush=True)
                if snap.get("lost"):
                    save_browser_error("WebGPU device lost during soak: " + str(snap.get("lost")))
                    return False
            measured = sum(samples) / len(samples) if samples else 0.0
            print("{:7.2f}s SOAK complete: {} samples, mean {:.1f} fps".format(
                time.time() - t0, len(samples), measured), flush=True)
            if a.soak_screenshot:
                try:
                    capture(a.soak_screenshot)
                except Exception as exc:
                    print("soak screenshot failed: {}: {}".format(type(exc).__name__, exc), flush=True)
            if a.min_fps > 0 and measured < a.min_fps:
                print("SOAK FPS FAIL: {:.1f} < {:.1f}".format(measured, a.min_fps), flush=True)
                return False
            return True

        def run_actions():
            nonlocal actions_done, actions_failed, action_hold_until
            if actions_done:
                return True
            if time.time() > actions_deadline:
                print("actions NOT finished within {:.0f}s".format(a.actions_timeout), flush=True)
                actions_done = True
                actions_failed = True
                return False
            if time.time() < action_hold_until:
                return False
            for act in actions:
                if act["done"]:
                    continue
                if act["kind"] == "wait":
                    try:
                        if js_value("!!(" + act["value"] + ")"):
                            print("action wait done: " + act["value"], flush=True)
                            act["done"] = True
                            continue
                    except TimeoutError:
                        pass
                    return False
                if act["kind"] == "shot":
                    try:
                        capture(act["value"])
                        print("action screenshot -> " + act["value"], flush=True)
                        act["done"] = True
                    except Exception as exc:
                        # Screenshots are evidence, not a precondition for the
                        # next UI action. A busy software-WebGPU compositor can
                        # refuse a capture request; retrying it forever would
                        # starve the actual clicks.
                        print("action screenshot failed (continuing): " + type(exc).__name__ + ": " + str(exc),
                              flush=True)
                        act["done"] = True
                    return False
                if act["kind"] == "cycle":
                    prefix, _, target = act["value"].partition("|")
                    # The action syntax already implies prefix matching: accept
                    # both "World Type" and "World Type*" so a stray star in a
                    # workflow does not turn into a literal "World Type*" match.
                    prefix = prefix.rstrip("*")
                    try:
                        raw = js_value("(() => { const f = document.getElementById('mc-brain');"
                                       " const w = f && f.contentWindow;"
                                       " return String((w && w.__mc && w.__mc['screen-buttons']) || ''); })()")
                    except TimeoutError:
                        return False
                    if target and target in str(raw or ""):
                        print("action cycle done: " + act["value"], flush=True)
                        act["done"] = True
                        continue
                    # The Java UI publishes widget text only after it renders a
                    # frame. Software WebGPU renders slowly (about ten seconds
                    # per frame on CI), so a fixed settle delay would queue
                    # several clicks into one frame and cycle straight past the
                    # requested value. Wait for screen-buttons to change after
                    # each click, with a timeout fallback in case the value
                    # repeats.
                    raw = str(raw or "")
                    now = time.time()
                    if act["clicks"] and now < act["next_click_at"]:
                        if raw == act["last_raw"]:
                            return False
                        act["next_click_at"] = 0.0
                    spec = prefix + "*"
                    try:
                        found = js_value(find_button_js % json.dumps(spec))
                    except TimeoutError:
                        return False
                    if not found:
                        # Stay quiet for the first attempts, then report why the
                        # action cannot progress instead of burning the action
                        # deadline without a single line in the log.
                        if now - act.get("last_miss", 0.0) >= 30.0:
                            print("action cycle: no button matching {!r} on {}".format(
                                spec, raw.split(" | ")[0][:120]), flush=True)
                            act["last_miss"] = now
                        return False
                    if act["clicks"] >= 16:
                        print("action cycle gave up after 16 clicks: " + act["value"], flush=True)
                        act["done"] = True
                        continue
                    if not click_at(found["x"], found["y"]):
                        return False
                    act["clicks"] += 1
                    act["last_raw"] = raw
                    act["next_click_at"] = now + 30.0
                    action_hold_until = time.time() + a.action_settle
                    print("action cycle click {}/16 {} at {:.1f},{:.1f}".format(
                        act["clicks"], found["label"], found["x"], found["y"]), flush=True)
                    return False
                # click
                try:
                    found = js_value(find_button_js % json.dumps(act["value"]))
                except TimeoutError:
                    return False
                if not found:
                    return False
                if not click_at(found["x"], found["y"]):
                    return False
                print("action click '{}' on {} at {:.1f},{:.1f}".format(
                    found["label"], found["screen"].split('.')[-1], found["x"], found["y"]),
                    flush=True)
                act["done"] = True
                action_hold_until = time.time() + a.action_settle
                return False
            actions_done = True
            return True

        deadline = time.time() + a.timeout
        last_state = 0.0
        last_action = 0.0
        while time.time() < deadline:
            try:
                msg = ws.recv(0.5)
            except (EOFError, OSError) as exc:
                crashed["flag"] = True
                crashed["reason"] = ("DevTools connection closed ({}): renderer likely crashed (Aw Snap)"
                                     .format(type(exc).__name__))
                save_browser_error(crashed["reason"])
                print("{:7.2f}s CRASH: {}".format(time.time() - t0, crashed["reason"]), flush=True)
                break
            if crashed["flag"]:
                print("{:7.2f}s CRASH: {}".format(time.time() - t0, crashed["reason"]), flush=True)
                break
            if msg:
                pump(msg)
                if not actions_done and time.time() - last_action >= 0.4:
                    last_action = time.time()
                    run_actions()
                continue
            if not actions_done:
                if time.time() - last_action >= 0.2:
                    last_action = time.time()
                    run_actions()
                if not actions_done:
                    try:
                        failed = ws.call("Runtime.evaluate",
                                         {"expression": fail_js, "returnByValue": True},
                                         pump=pump, timeout_sec=10)
                        if failed.get("result", {}).get("result", {}).get("value"):
                            print(f"{time.time() - t0:7.2f}s page reported failure", flush=True)
                            break
                    except TimeoutError:
                        pass
                    continue
            if a.state_every and time.time() - last_state >= a.state_every:
                last_state = time.time()
                try:
                    r = ws.call("Runtime.evaluate", {"expression": state_js, "returnByValue": True},
                                pump=pump, timeout_sec=5)
                    value = r.get("result", {}).get("result", {}).get("value")
                    line = "{:7.2f}s STATE: {}".format(time.time() - t0, value)
                    record(line)
                    print(line[:12000], flush=True)
                except TimeoutError:
                    print(f"{time.time() - t0:7.2f}s STATE: evaluate stalled; main thread may be blocked", flush=True)
            try:
                r = ws.call("Runtime.evaluate", {"expression": f"!!({a.until_js})", "returnByValue": True},
                            pump=pump, timeout_sec=10)
            except TimeoutError:
                print(f"{time.time() - t0:7.2f}s CDP evaluate stalled; main thread may be blocked", flush=True)
                continue
            if r.get("result", {}).get("result", {}).get("value"):
                ok = True
                break
            try:
                failed = ws.call("Runtime.evaluate", {"expression": fail_js, "returnByValue": True},
                                 pump=pump, timeout_sec=10)
                if failed.get("result", {}).get("result", {}).get("value"):
                    print(f"{time.time() - t0:7.2f}s page reported failure", flush=True)
                    break
            except TimeoutError:
                pass
        if actions_failed:
            ok = False
        print(f"{time.time() - t0:7.2f}s condition {'met' if ok else 'NOT met (timeout)'}", flush=True)
        # Capture the visible page as soon as the wait condition is met: this is the
        # only direct evidence that the real client (not just its logs) rendered.
        if a.screenshot and ok:
            try:
                capture(a.screenshot)
            except Exception as exc:
                print(f"screenshot unavailable: {type(exc).__name__}: {exc}", flush=True)
        if not ok:
            try:
                state_expr = "JSON.stringify({url:location.href,readyState:document.readyState,title:document.title,body:(document.body&&document.body.innerText||'').slice(0,4000),mc:window.__mc||null,bodyReady:window.__mcBodyReady,bodyError:window.__mcBodyError,bridge:window.mcBridge&&window.mcBridge.state?{error:window.mcBridge.state.error,sections:window.mcBridge.state.sections}:null,iframe:(()=>{const f=document.getElementById('mc-brain');return f?{src:f.src,readyState:f.contentDocument&&f.contentDocument.readyState,mc:f.contentWindow&&f.contentWindow.__mc}:null})()})"
                state_result = ws.call("Runtime.evaluate", {"expression": state_expr, "returnByValue": True}, pump=pump, timeout_sec=10)
                state_value = state_result.get("result", {}).get("result", {}).get("value")
                if state_value:
                    save_browser_error("CDP timeout page state: " + str(state_value))
                    print("timeout page state: " + str(state_value)[:8000], flush=True)
            except Exception as exc:
                save_browser_error("Could not read timeout page state: " + type(exc).__name__ + ": " + str(exc))
            diagnostic_path = a.screenshot or "cdp-timeout.png"
            try:
                capture(diagnostic_path)
                print(f"timeout screenshot -> {diagnostic_path}", flush=True)
            except Exception as exc:
                print(f"timeout screenshot unavailable: {type(exc).__name__}: {exc}", flush=True)
        assert_ok = True
        if ok:
            for expr in a.assert_js:
                try:
                    r = ws.call("Runtime.evaluate", {"expression": f"!!({expr})", "returnByValue": True}, pump=pump)
                    passed = bool(r.get("result", {}).get("result", {}).get("value"))
                    print(f"assert {expr} => {'PASS' if passed else 'FAIL'}")
                    assert_ok = assert_ok and passed
                except (TimeoutError, EOFError, OSError) as exc:
                    print(f"assert evaluation unavailable: {type(exc).__name__}: {exc}", flush=True)
                    assert_ok = False
                    break
            if assert_ok:
                assert_ok = run_soak()
            if assert_ok:
                for expr in a.eval:
                    try:
                        r = ws.call("Runtime.evaluate", {"expression": expr, "returnByValue": True}, pump=pump)
                        print(f"eval {expr} => {json.dumps(r.get('result', {}).get('result', {}).get('value'))[:4000]}")
                    except (TimeoutError, EOFError, OSError) as exc:
                        print(f"eval unavailable: {type(exc).__name__}: {exc}", flush=True)
                        assert_ok = False
                        break
        else:
            print("skipping post-timeout assertions/evaluations: page did not become responsive", flush=True)
            assert_ok = False
        if a.screenshot and not ok:
            print("skipping requested screenshot: page did not become responsive", flush=True)
    finally:
        flush_diagnostics()
        try:
            if error_count == 0:
                error_file.write("No Chrome compile/runtime error was captured by CDP. "
                                 "Check the smoke-test step output for timeout or assertion details.\n")
                error_file.flush()
            error_file.close()
            console_file.close()
        except Exception:
            pass
        print("Full browser console -> build/browser_console.log", flush=True)
        print("Browser diagnostics -> build/browser_error.txt", flush=True)
        chrome.kill()
        chrome.wait()
        shutil.rmtree(profile, ignore_errors=True)
    sys.exit(0 if ok and assert_ok else 1)


if __name__ == "__main__":
    main()
