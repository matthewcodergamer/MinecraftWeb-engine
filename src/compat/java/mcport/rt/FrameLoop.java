package mcport.rt;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * The browser shows a frame only when JavaScript returns to the event loop, so Minecraft's (green) render thread
 * suspends at the end of every frame: until the next requestAnimationFrame with vsync, or for one macrotask without.
 * This is the browser counterpart of the swap-buffers wait in the desktop client.
 */
public final class FrameLoop {
    private static boolean vsync = true;
    private static final java.util.List<Runnable> HOOKS = new java.util.ArrayList<>();

    private FrameLoop() {
    }

    public static void setVsync(boolean on) {
        vsync = on;
    }

    /** Adds a browser-side per-frame consumer (chunk bridge, input bridge, ...). */
    public static void addHook(Runnable hook) {
        HOOKS.add(hook);
    }

    public static void endFrame() {
        BrowserRuntime.report("trace", "frame-loop-endframe-enter");
        long hooksStart = System.nanoTime();
        for (int i = 0; i < HOOKS.size(); i++) {
            try {
                HOOKS.get(i).run();
            } catch (Throwable ignored) {
                // A visualization hook must never kill the Java game loop.
            }
        }
        frameStats();
        FrameProfiler.hooks(System.nanoTime() - hooksStart);
        BrowserRuntime.report("trace", "frame-loop-wait-enter");
        FrameLoopAsync.waitForNextFrame(vsync);
    }

    @JSBody(script = ""
            + "var s = window.__mcFrames || (window.__mcFrames = {count: 0, last: performance.now(), fps: 0, frameMs: 0});"
            + "var now = performance.now(); s.count++;"
            + "if (s.resumeAt) { var w = now - s.resumeAt; s.workMs = w;"
            + "  s.workAvg = s.workAvg ? s.workAvg * 0.9 + w * 0.1 : w; s.resumeAt = 0; }"
            + "if (now - s.last >= 1000) { s.fps = s.count * 1000 / (now - s.last); s.count = 0; s.last = now;"
            + "  if (window.__mcOnFps) window.__mcOnFps(s.fps); }")
    private static native void frameStats();
}
