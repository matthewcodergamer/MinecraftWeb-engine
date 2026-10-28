package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;

class WebGpuSampler implements GpuSampler {
    final int id;
    private final AddressMode u;
    private final AddressMode v;
    private final FilterMode min;
    private final FilterMode mag;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed;

    WebGpuSampler(AddressMode u, AddressMode v, FilterMode min, FilterMode mag, int maxAnisotropy, OptionalDouble maxLod) {
        this.u = u;
        this.v = v;
        this.min = min;
        this.mag = mag;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
        this.id = WG.createSampler(u.ordinal(), v.ordinal(), min.ordinal(), mag.ordinal(), maxAnisotropy,
                maxLod.isPresent() ? maxLod.getAsDouble() : -1);
    }

    @Override public AddressMode getAddressModeU() { return u; }
    @Override public AddressMode getAddressModeV() { return v; }
    @Override public FilterMode getMinFilter() { return min; }
    @Override public FilterMode getMagFilter() { return mag; }
    @Override public int getMaxAnisotropy() { return maxAnisotropy; }
    @Override public OptionalDouble getMaxLod() { return maxLod; }
    @Override public boolean isClosed() { return closed; }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            WG.destroySampler(id);
        }
    }
}
