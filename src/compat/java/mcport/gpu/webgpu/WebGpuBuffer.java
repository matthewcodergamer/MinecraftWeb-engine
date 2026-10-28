package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.common.BaseGpuBuffer;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** GpuBuffer backed by a GPUBuffer; map() uses a CPU copy in the native heap (WebGPU mapping is asynchronous). */
class WebGpuBuffer extends BaseGpuBuffer {
    final int id;
    private boolean closed;

    WebGpuBuffer(int usage, long size, String label) {
        super(usage, size);
        this.id = WG.createBuffer(size, usage, label);
    }

    /** View of a transient upload chunk (not owned). */
    WebGpuBuffer(int usage, long size, int existingId) {
        super(usage, size);
        this.id = existingId;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            WG.destroyBuffer(id);
        }
    }

    @Override
    public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
        int base = WG.mapBuffer(id);
        ByteBuffer data = MemoryUtil.memByteBuffer(base + offset, (int) length);
        return new GpuBufferSlice.MappedView(new GpuBufferSlice(this, offset, length), data,
                () -> WG.unmapBuffer(id, offset, length, write));
    }
}
