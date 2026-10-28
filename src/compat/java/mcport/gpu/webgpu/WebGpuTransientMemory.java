package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-submit transient memory. GPU-visible allocations live in upload chunks (web/gpu/webgpu.js) whose CPU copies
 * are written to the GPU with queue.writeBuffer right before the command buffer is submitted, which WebGPU orders
 * ahead of that command buffer - the same visibility guarantee as Vulkan's persistently mapped memory.
 */
class WebGpuTransientMemory implements TransientMemory {
    private static final int ALL_USAGE = 0x3FF;
    private static final int CPU_BLOCK = 1 << 20;
    private final Int2ObjectOpenHashMap<WebGpuTransientBuffer> chunks = new Int2ObjectOpenHashMap<>();
    private final List<long[]> cpuBlocks = new ArrayList<>();
    private int cpuBlock;
    private long cpuUsed;

    void reset() {
        cpuBlock = 0;
        cpuUsed = 0;
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
        long align = Math.max(1, alignment);
        while (true) {
            if (cpuBlock == cpuBlocks.size()) {
                long blockSize = Math.max(CPU_BLOCK, size + align);
                cpuBlocks.add(new long[] {MemoryUtil.nmemAlloc(blockSize), blockSize});
            }
            long[] b = cpuBlocks.get(cpuBlock);
            long start = (b[0] + cpuUsed + align - 1) / align * align;
            if (start + size <= b[0] + b[1]) {
                cpuUsed = start + size - b[0];
                return MemoryUtil.memByteBuffer(start, (int) size);
            }
            cpuBlock++;
            cpuUsed = 0;
        }
    }

    private WebGpuTransientBuffer chunk(int id) {
        WebGpuTransientBuffer b = chunks.get(id);
        if (b == null) {
            b = new WebGpuTransientBuffer(ALL_USAGE, 1L << 31, id);
            chunks.put(id, b);
        }
        return b;
    }

    private GpuBufferSlice.MappedView mapped(long size, long alignment) {
        long align = Math.max(4, alignment);
        int offset = WG.transientAlloc(size, align);
        int id = WG.lastTransientId();
        int address = WG.lastTransientAddress();
        GpuBufferSlice slice = new GpuBufferSlice(chunk(id), offset, size);
        return new GpuBufferSlice.MappedView(slice, MemoryUtil.memByteBuffer(address + offset, (int) size), () -> { });
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        return mapped(size, alignment);
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        return mapped(size, alignment).slice();
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
        return mapped(size, alignment);
    }

    private GpuBufferSlice upload(List<ByteBuffer> data, long alignment) {
        long align = Math.max(4, alignment);
        long total = 0;
        for (ByteBuffer b : data) {
            total += b.remaining();
            total = (total + align - 1) / align * align;
        }
        GpuBufferSlice.MappedView view = mapped(Math.max(total, 4), align);
        long dst = MemoryUtil.memAddress(view.data());
        long offset = 0;
        for (ByteBuffer b : data) {
            MemoryUtil.memCopy(MemoryUtil.memAddress(b), dst + offset, b.remaining());
            offset += b.remaining();
            offset = (offset + align - 1) / align * align;
        }
        return view.slice();
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
        return upload(data, alignment);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
        return upload(data, alignment);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
        List<GpuBufferSlice> r = new ArrayList<>(data.size());
        for (ByteBuffer b : data) {
            r.add(upload(List.of(b), alignment));
        }
        return r;
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
        return multiUploadStaging(data, alignment, usage);
    }
}
