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
import socket
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request

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
        while True:
            msg = self.recv(timeout_sec)
            if msg is None:
                raise TimeoutError(method)
            if msg.get("id") == mid:
                return msg
            if pump:
                pump(msg)


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
    a = ap.parse_args()

    profile = tempfile.mkdtemp()
    port = 9300 + os.getpid() % 500
    args = [CHROME, "--headless=new", f"--remote-debugging-port={port}", f"--user-data-dir={profile}",
            "--no-proxy-server",
            "--no-first-run", "--window-size=1280,720", "--enable-unsafe-webgpu", "about:blank"]
    if not a.gpu:
        # GitHub-hosted Linux runners have no physical GPU; explicitly select
        # SwiftShader's Vulkan backend for WebGPU as well as ANGLE for WebGL.
        args[1:1] = [
            "--enable-features=Vulkan",
            "--use-vulkan=swiftshader",
            "--disable-vulkan-surface",
            "--use-angle=swiftshader",
        ]
    chrome = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    ok = False
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

        def pump(msg):
            if a.quiet:
                return
            m = msg.get("method")
            if m == "Runtime.consoleAPICalled":
                text = " ".join(str(x.get("value", x.get("description", ""))) for x in msg["params"]["args"])
                print(f"{time.time() - t0:7.2f}s console.{msg['params']['type']}: {text[:2000]}", flush=True)
            elif m == "Runtime.exceptionThrown":
                d = msg["params"]["exceptionDetails"]
                print(f"{time.time() - t0:7.2f}s EXCEPTION: {d.get('exception', {}).get('description', d.get('text'))[:3000]}",
                      flush=True)

        ws.call("Runtime.enable", pump=pump)
        ws.call("Page.enable", pump=pump)
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
        deadline = time.time() + a.timeout
        while time.time() < deadline:
            msg = ws.recv(0.5)
            if msg:
                pump(msg)
                continue
            try:
                r = ws.call("Runtime.evaluate", {"expression": f"!!({a.until_js})", "returnByValue": True},
                            pump=pump, timeout_sec=10)
            except TimeoutError:
                print(f"{time.time() - t0:7.2f}s CDP evaluate stalled; main thread may be blocked", flush=True)
                continue
            if r.get("result", {}).get("result", {}).get("value"):
                ok = True
                break
        print(f"{time.time() - t0:7.2f}s condition {'met' if ok else 'NOT met (timeout)'}", flush=True)
        if not ok:
            diagnostic_path = a.screenshot or "cdp-timeout.png"
            try:
                shot = ws.call("Page.captureScreenshot", {"format": "png"}, pump=pump, timeout_sec=10)
                with open(diagnostic_path, "wb") as f:
                    f.write(base64.b64decode(shot["result"]["data"]))
                print(f"timeout screenshot -> {diagnostic_path}", flush=True)
            except Exception as exc:
                print(f"timeout screenshot unavailable: {type(exc).__name__}: {exc}", flush=True)
        assert_ok = True
        for expr in a.assert_js:
            r = ws.call("Runtime.evaluate", {"expression": f"!!({expr})", "returnByValue": True}, pump=pump)
            passed = bool(r.get("result", {}).get("result", {}).get("value"))
            print(f"assert {expr} => {'PASS' if passed else 'FAIL'}")
            assert_ok = assert_ok and passed
        for expr in a.eval:
            r = ws.call("Runtime.evaluate", {"expression": expr, "returnByValue": True}, pump=pump)
            print(f"eval {expr} => {json.dumps(r.get('result', {}).get('result', {}).get('value'))[:4000]}")
        if a.screenshot:
            r = ws.call("Page.captureScreenshot", {"format": "png"}, pump=pump)
            with open(a.screenshot, "wb") as f:
                f.write(base64.b64decode(r["result"]["data"]))
            print(f"screenshot -> {a.screenshot}")
    finally:
        chrome.kill()
        chrome.wait()
        shutil.rmtree(profile, ignore_errors=True)
    sys.exit(0 if ok and assert_ok else 1)


if __name__ == "__main__":
    main()
