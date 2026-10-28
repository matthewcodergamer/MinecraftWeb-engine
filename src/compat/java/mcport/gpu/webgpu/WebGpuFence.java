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
        // suspends this (green) thread until the GPU work is done; the browser keeps running meanwhile
        await(id);
        return true;
    }

    @Async
    private static native void await(int id);

    private static void await(int id, AsyncCallback<Void> callback) {
        whenDone(id, () -> callback.complete(null));
    }

    @JSFunctor
    interface Done extends JSObject {
        void run();
    }

    @JSBody(params = {"id", "done"}, script = "__mcGPU.fencePromise(id).then(function() { done(); });")
    private static native void whenDone(int id, Done done);
}
