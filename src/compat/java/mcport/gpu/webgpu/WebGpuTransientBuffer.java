package mcport.gpu.webgpu;

/** A transient upload chunk as seen by renderpearl: valid until the next submit; never closed by callers. */
final class WebGpuTransientBuffer extends WebGpuBuffer {
    WebGpuTransientBuffer(int usage, long size, int id) {
        super(usage, size, id);
    }

    @Override
    public boolean isClosed() {
        return false;
    }

    @Override
    public void close() {
    }
}
