package mcport.rt;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Isolates the TeaVM async transformation for the browser frame wait from FrameLoop.
 * The callback implementation must be entered by TeaVM before any browser timer can
 * resume the render fiber.
 */
public final class FrameLoopAsync {
    private FrameLoopAsync() {
    }

    @Async
    public static native void waitForNextFrame(boolean vsync);

    private static void waitForNextFrame(boolean vsync, AsyncCallback<Void> callback) {
        BrowserRuntime.report("trace", "frame-loop-schedule-requested");
        schedule(vsync, () -> {
            BrowserRuntime.report("trace", "frame-loop-resume-callback");
            callback.complete(null);
        });
    }

    @JSFunctor
    interface Resume extends JSObject {
        void run();
    }

    @JSBody(params = {"vsync", "resume"}, script = ""
            + "var done = false; var finish = function(source) { if (done) return; done = true;"
            + " if (source === 'raf-fallback') console.warn('[FRAME_LOOP] RAF stalled; resumed via fallback');"
            + " var f = window.__mcFrames; if (f) f.resumeAt = performance.now();"
            + " resume(); };"
            + "if (vsync || document.hidden) {"
            + " try { requestAnimationFrame(function() { finish('raf'); }); }"
            + " catch (e) { console.warn('[FRAME_LOOP] RAF unavailable', e); }"
            + " setTimeout(function() { finish('raf-fallback'); }, 250); return; }"
            + "var ch = window.__mcFrameChannel || (window.__mcFrameChannel = new MessageChannel());"
            + "ch.port1.onmessage = function() { finish('message-channel'); }; ch.port2.postMessage(0);")
    private static native void schedule(boolean vsync, Resume resume);
}
