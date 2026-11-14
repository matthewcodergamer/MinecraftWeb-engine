package mcport.gpu;

import com.mojang.renderpearl.api.device.BackendCreationException;
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

    /**
     * VulkanBackend.checkBackendAvailable: there is no Vulkan loader in a browser and renderpearl's probe
     * loads it and builds @NativeType structs. Report the backend as unavailable so the client keeps the
     * reason in its diagnostics and moves on to the WebGPU backend above.
     */
    public static BackendCreationException vulkanUnavailable() {
        return new BackendCreationException("Vulkan is not available in the browser",
                BackendCreationException.Reason.VULKAN_LOADER_MISSING);
    }
}
