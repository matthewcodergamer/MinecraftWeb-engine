package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.commands.GpuFence;

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
        // RenderSystem uses timeoutNS == 0 as a non-blocking poll.
        if (timeoutNS == 0) {
            return false;
        }

        // Do not resume a TeaVM @Async continuation from the WebGPU Promise
        // callback. That path can strand the Render thread inside TeaVM's
        // monitor-notification machinery. Poll the JS-side completion flag
        // while yielding the green thread so the browser event loop can settle
        // queue.onSubmittedWorkDone() and update it.
        long started = System.nanoTime();
        while (!WG.fenceDone(id)) {
            if (timeoutNS > 0 && System.nanoTime() - started >= timeoutNS) {
                return false;
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
