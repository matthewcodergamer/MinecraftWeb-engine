package mcport.gpu.webgpu;

import com.mojang.renderpearl.backend.common.BaseGpuTextureView;

class WebGpuTextureView extends BaseGpuTextureView {
    final int id;
    private boolean closed;

    WebGpuTextureView(WebGpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        this.id = WG.createView(texture.id, baseMipLevel, mipLevels);
    }

    @Override
    public boolean isClosed() {
        return closed || texture().isClosed();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            WG.destroyView(id);
        }
    }
}
