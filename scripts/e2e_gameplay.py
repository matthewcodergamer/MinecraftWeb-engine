#!/usr/bin/env python3
"""End-to-end gameplay check for the real Java client in a browser.

This drives the same Java UI a player uses, creates a Superflat world, breaks a
block through the real SDL input path, opens the inventory with E, and presses
1-9 to switch hotbar slots. It fails on any Java/page exception.

usage:
    python3 scripts/e2e_gameplay.py https://matthewcodergamer-minecraft-browser.static.hf.space/
"""
import argparse
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.request

from cdp import WS

CHROME = os.environ.get(
    "CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
)

FIND_BUTTON_JS = """(() => {
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

WRAP_SECTIONS_JS = """(() => {
    const f = document.getElementById('mc-brain');
    const w = f && f.contentWindow;
    const b = w && w.mcBridge;
    if (!b) return 'no-bridge';
    if (!b.__e2eOrig) {
        b.__e2eOrig = b.receiveJavaSection.bind(b);
        w.__e2eHashes = {};
        w.__e2eEmits = 0;
        b.receiveJavaSection = function (cx, cy, cz, ids) {
            try {
                let h = 2166136261 >>> 0;
                const a = ids instanceof Int32Array ? ids : new Int32Array(ids);
                for (let i = 0; i < a.length; i++) {
                    h ^= a[i];
                    h = Math.imul(h, 16777619) >>> 0;
                }
                w.__e2eHashes[cx + ',' + cy + ',' + cz] = h;
                w.__e2eEmits++;
            } catch (e) { /* diagnostics must not break the game */ }
            return b.__e2eOrig(cx, cy, cz, ids);
        };
    }
    return 'wrapped';
})()"""


class E2E:
    def __init__(self, args):
        self.args = args
        self.profile = tempfile.mkdtemp(prefix="mc-e2e-")
        self.port = 9500 + os.getpid() % 300
        self.chrome = None
        self.ws = None
        self.errors = []
        self.t0 = time.time()

    def log(self, message):
        print(f"{time.time() - self.t0:7.1f}s {message}", flush=True)

    def start(self):
        args = [
            CHROME,
            "--headless=new",
            "--no-sandbox",
            f"--remote-debugging-port={self.port}",
            f"--user-data-dir={self.profile}",
            "--no-proxy-server",
            "--no-first-run",
            "--window-size=1280,720",
            "--enable-unsafe-webgpu",
            "--enable-features=Vulkan",
            "--use-vulkan=swiftshader",
            "--disable-vulkan-surface",
            "--use-angle=swiftshader",
            "about:blank",
        ]
        extra = os.environ.get("CHROME_FLAGS", "").split()
        if extra:
            args[1:1] = extra
        self.chrome = subprocess.Popen(
            args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
        )
        page = None
        for _ in range(600):
            try:
                tabs = json.load(urllib.request.urlopen(f"http://127.0.0.1:{self.port}/json"))
                page = next(t for t in tabs if t["type"] == "page")
                break
            except Exception:
                time.sleep(0.1)
        if page is None:
            raise SystemExit("Chrome did not start")
        self.ws = WS(page["webSocketDebuggerUrl"])

    def pump(self, msg):
        method = msg.get("method")
        if method == "Runtime.exceptionThrown":
            detail = msg["params"]["exceptionDetails"].get("exception", {}).get(
                "description", msg["params"]["exceptionDetails"].get("text", "")
            )
            self.errors.append("browser exception: " + str(detail))
            self.log("EXCEPTION: " + str(detail)[:500])
        elif method == "Log.entryAdded":
            entry = msg["params"].get("entry", {})
            if entry.get("level") == "error":
                self.errors.append("chrome error: " + str(entry.get("text", "")))
                self.log("CHROME ERROR: " + str(entry.get("text", ""))[:500])
        elif method == "Inspector.targetCrashed":
            self.errors.append("renderer crashed")
            self.log("RENDERER CRASHED")

    def eval(self, expr, timeout=60):
        result = self.ws.call(
            "Runtime.evaluate",
            {"expression": expr, "returnByValue": True},
            pump=self.pump,
            timeout_sec=timeout,
        )
        return result.get("result", {}).get("result", {}).get("value")

    def wait(self, expr, description, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                if self.eval(f"!!({expr})", timeout=30):
                    self.log("ready: " + description)
                    return True
            except TimeoutError:
                self.log("evaluate stalled while waiting for " + description)
            time.sleep(1.0)
        raise SystemExit("timed out waiting for " + description)

    def click_button(self, label, screen="", timeout=180):
        spec = label + ("@" + screen if screen else "")
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                found = self.eval(FIND_BUTTON_JS % json.dumps(spec), timeout=30)
            except TimeoutError:
                found = None
            if found:
                x, y = found["x"], found["y"]
                for kind, buttons in (("mouseMoved", 0), ("mousePressed", 1), ("mouseReleased", 0)):
                    self.ws.call(
                        "Input.dispatchMouseEvent",
                        {"type": kind, "x": x, "y": y, "button": "left",
                         "buttons": buttons, "clickCount": 1},
                        pump=self.pump,
                        timeout_sec=30,
                    )
                self.log(f"clicked {found['label']} on {found['screen'].split('.')[-1]}")
                time.sleep(1.0)
                return True
            time.sleep(1.0)
        raise SystemExit("button not found: " + spec)

    def cycle_until(self, prefix, target, timeout=300):
        """Click a Java option button until its rendered value contains target."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            raw = self.eval(
                "(() => { const f=document.getElementById('mc-brain');"
                " return String((f && f.contentWindow && f.contentWindow.__mc"
                " && f.contentWindow.__mc['screen-buttons']) || ''); })()"
            )
            if target in str(raw or ""):
                self.log("option selected: " + target)
                return
            found = self.eval(
                FIND_BUTTON_JS % json.dumps(prefix + "*@CreateWorldScreen"), timeout=30
            )
            if found:
                for kind, buttons in (("mouseMoved", 0), ("mousePressed", 1), ("mouseReleased", 0)):
                    self.ws.call(
                        "Input.dispatchMouseEvent",
                        {"type": kind, "x": found["x"], "y": found["y"], "button": "left",
                         "buttons": buttons, "clickCount": 1},
                        pump=self.pump,
                        timeout_sec=30,
                    )
                self.log("cycled " + prefix)
            time.sleep(2.0)
        raise SystemExit("could not select " + target)

    def screenshot(self, path):
        try:
            result = self.ws.call(
                "Page.captureScreenshot", {"format": "png"}, pump=self.pump, timeout_sec=60
            )
            import base64

            data = base64.b64decode(result["result"]["data"])
            os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
            with open(path, "wb") as f:
                f.write(data)
            self.log(f"screenshot -> {path}")
        except Exception as error:
            self.log(f"screenshot failed: {type(error).__name__}: {error}")

    def run(self):
        self.start()
        ws = self.ws
        ws.call("Runtime.enable", pump=self.pump)
        ws.call("Page.enable", pump=self.pump)
        ws.call("Inspector.enable", pump=self.pump)
        ws.call("Log.enable", pump=self.pump)
        ws.call("Page.navigate", {"url": self.args.url}, pump=self.pump)
        self.log("navigated to " + self.args.url)

        self.wait(
            "window.__mc && document.body.classList.contains('java-ui')",
            "Java title screen",
            self.args.title_timeout,
        )
        self.screenshot(self.args.screenshot.replace(".png", "-title.png"))

        self.click_button("Singleplayer", "TitleScreen", timeout=300)
        self.wait(
            "(() => { const f=document.getElementById('mc-brain');"
            " return String((f && f.contentWindow && f.contentWindow.__mc"
            " && f.contentWindow.__mc['screen-buttons']) || '').indexOf('CreateWorldScreen') >= 0; })()",
            "CreateWorldScreen",
            300,
        )
        self.click_button("World", "CreateWorldScreen")
        self.cycle_until("Game Mode", "Game Mode: Creative")
        self.cycle_until("Difficulty", "Difficulty: Peaceful")
        self.cycle_until("World Type", "World Type: Superflat")
        self.click_button("Create New World", "CreateWorldScreen")
        self.wait(
            "window.__mc && window.__mc.javaStage === 'first-java-section-emitted'",
            "Java world loaded",
            self.args.world_timeout,
        )
        self.screenshot(self.args.screenshot)

        self.wait(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; return !!(w && w.mcBridge && w.__mcSDL); })()",
            "Java bridge and SDL input",
            120,
        )
        wrapped = self.eval(WRAP_SECTIONS_JS)
        if wrapped != "wrapped":
            raise SystemExit("could not wrap chunk receiver: " + str(wrapped))
        self.log("chunk-state recorder installed")

        before = self.eval(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; return JSON.stringify(w.__e2eHashes||{}); })()"
        )
        emits_before = self.eval(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; return w.__e2eEmits||0; })()"
        )
        self.log("sections before break: " + str(emits_before))

        # Look straight down, then break the block under the player's feet with
        # the real SDL mouse path (creative mode breaks instantly).
        self.eval(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; w.__mcSDL.injectMouse(0, 700); return true; })()"
        )
        time.sleep(2.0)
        self.eval(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; w.__mcSDL.injectButton(0, true);"
            " return true; })()"
        )
        time.sleep(2.5)
        self.eval(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; w.__mcSDL.injectButton(0, false);"
            " return true; })()"
        )
        self.log("left-clicked to break the block below the player")

        changed = False
        deadline = time.time() + self.args.break_timeout
        while time.time() < deadline:
            after = self.eval(
                "(() => { const f=document.getElementById('mc-brain');"
                " const w=f&&f.contentWindow; return JSON.stringify(w.__e2eHashes||{}); })()"
            )
            if after != before:
                changed = True
                break
            time.sleep(1.0)
        if not changed:
            raise SystemExit("no chunk section update after breaking a block")
        self.log("chunk state updated after block break")

        # Inventory: E opens the real Java InventoryScreen, E closes it again.
        self.eval(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; w.__mcSDL.injectKey('KeyE', true);"
            " w.__mcSDL.injectKey('KeyE', false); return true; })()"
        )
        self.wait(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; return String((w&&w.__mc&&w.__mc['boot-state'])||'')"
            ".indexOf('InventoryScreen') >= 0; })()",
            "InventoryScreen open",
            120,
        )
        self.screenshot(self.args.screenshot.replace(".png", "-inventory.png"))
        self.eval(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; w.__mcSDL.injectKey('KeyE', true);"
            " w.__mcSDL.injectKey('KeyE', false); return true; })()"
        )
        self.wait(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; return String((w&&w.__mc&&w.__mc['boot-state'])||'')"
            ".indexOf('InventoryScreen') < 0; })()",
            "InventoryScreen closed",
            120,
        )

        # Exercise all nine hotbar slots through the same Digit1..Digit9 path.
        for slot in range(9):
            ok = self.eval(
                "(() => { const f=document.getElementById('mc-brain');"
                " const w=f&&f.contentWindow; return !!w.__mcSDL.injectSlot(%d); })()" % slot
            )
            if not ok:
                raise SystemExit(f"injectSlot({slot}) failed")
            time.sleep(0.25)
        self.log("pressed 1-9 through the Java hotbar path")

        state = self.eval(
            "(() => { const f=document.getElementById('mc-brain');"
            " const w=f&&f.contentWindow; const m=(w&&w.__mc)||{};"
            " return JSON.stringify({stage:m.stage,error:m.error||null,"
            "audio:m['audio-backend']||null,boot:m['boot-state']||null}); })()"
        )
        self.log("final Java state: " + str(state))
        if self.errors:
            raise SystemExit("browser/Java errors: " + "; ".join(self.errors[:5]))
        if '"error":null' not in str(state) and '"error":' in str(state):
            # The state is JSON; only a non-null error is a failure.
            parsed = json.loads(state)
            if parsed.get("error"):
                raise SystemExit("Java error: " + str(parsed["error"]))
        self.screenshot(self.args.screenshot.replace(".png", "-final.png"))
        self.log("E2E PASS")

    def close(self):
        try:
            if self.chrome:
                self.chrome.send_signal(signal.SIGTERM)
                self.chrome.wait(timeout=10)
        except Exception:
            try:
                self.chrome.kill()
            except Exception:
                pass
        shutil.rmtree(self.profile, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("url")
    parser.add_argument("--screenshot", default="build/e2e_gameplay.png")
    parser.add_argument("--title-timeout", type=float, default=1800)
    parser.add_argument("--world-timeout", type=float, default=1200)
    parser.add_argument("--break-timeout", type=float, default=120)
    args = parser.parse_args()
    e2e = E2E(args)
    try:
        e2e.run()
    finally:
        e2e.close()


if __name__ == "__main__":
    main()
