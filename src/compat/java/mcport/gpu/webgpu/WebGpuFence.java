package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.commands.GpuFence;
import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/** Signalled when all GPU work submitted before its creation has completed (queue.onSubmittedWorkDone). */
class WebGpuFence implements GpuFence {
    private final int id;
    private boolean closed;

    WebGpuFence() {
        this.id = WG.createFence();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            WG.destroyFence(id);
        }
    }

    @Override
    public boolean awaitCompletion(long timeoutNS) {
        if (WG.fenceDone(id)) {
            return true;
        }
        if (timeoutNS == 0) {
            return false;
        }
        traceAwait(id);
        // suspends this (green) thread until the GPU work is done; the browser keeps running meanwhile
        await(id);
        traceResumed(id);
        return true;
    }

    @Async
    private static native void await(int id);

    private static void await(int id, AsyncCallback<Void> callback) {
        whenDone(id, () -> {
            traceResolved(id);
            callback.complete(null);
        }, message -> {
            traceRejected(id);
            callback.error(new RuntimeException(message));
        });
    }

    @JSFunctor
    interface Done extends JSObject {
        void run();
    }

    @JSFunctor
    interface Failed extends JSObject {
        void run(String message);
    }

    @JSBody(params = {"id"}, script = "console.log('[WEBGPU_FENCE] await-enter id=' + id);")
    private static native void traceAwait(int id);

    @JSBody(params = {"id"}, script = "console.log('[WEBGPU_FENCE] await-resumed id=' + id);")
    private static native void traceResumed(int id);

    @JSBody(params = {"id"}, script = "console.log('[WEBGPU_FENCE] promise-resolved id=' + id);")
    private static native void traceResolved(int id);

    @JSBody(params = {"id"}, script = "console.error('[WEBGPU_FENCE] promise-rejected id=' + id);")
    private static native void traceRejected(int id);

    @JSBody(params = {"id", "done", "failed"}, script = "var started = Date.now(); console.log('[WEBGPU_FENCE] promise-start id=' + id); var timer = setTimeout(function() { console.error('[WEBGPU_FENCE] promise-timeout id=' + id + ' elapsed=' + (Date.now() - started) + 'ms'); }, 30000); Promise.resolve().then(function() { return __mcGPU.fencePromise(id); }).then(function() { clearTimeout(timer); console.log('[WEBGPU_FENCE] promise-fulfilled id=' + id + ' elapsed=' + (Date.now() - started) + 'ms'); done(); }, function(error) { clearTimeout(timer); var message = String(error && (error.stack || error.message) || error); console.error('[WEBGPU_FENCE] promise-rejected id=' + id + ' elapsed=' + (Date.now() - started) + 'ms ' + message); failed(message); });")
    private static native void whenDone(int id, Done done, Failed failed);
}
