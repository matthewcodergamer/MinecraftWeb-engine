package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;

class WebGpuTexture extends BaseGpuTexture {
    final int id;
    private boolean closed;

    WebGpuTexture(int usage, String label, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.id = WG.createTexture(label, usage, format.ordinal(), width, height, depthOrLayers, mipLevels);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            WG.destroyTexture(id);
        }
    }
}
