package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.commands.GpuQueryPool;
import java.util.Arrays;
import java.util.OptionalLong;

/** Timestamp queries need the optional 'timestamp-query' feature; results are reported as unavailable. */
class WebGpuQueryPool implements GpuQueryPool {
    private final int size;

    WebGpuQueryPool(int size) {
        this.size = size;
    }

    @Override public int size() { return size; }
    @Override public OptionalLong getValue(int index) { return OptionalLong.empty(); }

    @Override
    public OptionalLong[] getValues(int index, int count) {
        OptionalLong[] r = new OptionalLong[count];
        Arrays.fill(r, OptionalLong.empty());
        return r;
    }

    @Override public void close() { }
}
