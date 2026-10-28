package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import java.util.Collection;
import java.util.List;
import mcport.rt.FrameLoop;

/** The canvas swap chain. present() submits the frame and yields to the browser until the next animation frame. */
class WebGpuSurface implements GpuSurfaceBackend {
    private final WebGpuCommandEncoder encoder;

    WebGpuSurface(WebGpuCommandEncoder encoder) {
        this.encoder = encoder;
    }

    @Override
    public void configure(GpuSurface.Configuration config) {
        WG.configureSurface(config.width(), config.height(), config.presentMode() == GpuSurface.PresentMode.FIFO);
        FrameLoop.setVsync(config.presentMode() == GpuSurface.PresentMode.FIFO);
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() {
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend commandEncoder, GpuTextureView view) {
        WG.blitToSurface(((WebGpuTextureView) view).id);
    }

    @Override
    public void present() {
        WG.present();
        FrameLoop.endFrame();
        encoder.runCompletedReadbacks();
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return List.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.MAILBOX);
    }

    @Override
    public void close() {
    }
}
