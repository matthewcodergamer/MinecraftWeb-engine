package mcport.gpu;

import com.mojang.renderpearl.api.device.GpuBackend;
import mcport.gpu.webgpu.WebGpuBackend;
import net.minecraft.client.PreferredGraphicsApi;

/**
 * PreferredGraphicsApi.getBackendsToTry(): the browser's graphics APIs in order of preference. Minecraft's own
 * startup code tries each in turn (falling back on BackendCreationException) exactly as with Vulkan/OpenGL.
 */
public final class GraphicsPatches {
    private GraphicsPatches() {
    }

    public static GpuBackend[] backendsToTry(PreferredGraphicsApi api) {
        return new GpuBackend[] {new WebGpuBackend()};
    }
}
