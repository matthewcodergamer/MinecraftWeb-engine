package mcport.gpu.webgpu;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.lwjgl.sdl.SDLVideo;

/**
 * renderpearl backend over WebGPU - the default graphics API of the browser port (preferred over WebGL2).
 * Everything above it (Mojang's frontend, pipelines, world/GUI renderers) is the unmodified client.
 */
public class WebGpuBackend implements GpuBackend {
    @Override
    public String getName() {
        return "WebGPU";
    }

    @Override
    public void loadLibrary() throws BackendCreationException {
        if (!WG.available()) {
            throw new BackendCreationException("WebGPU is not available in this browser", BackendCreationException.Reason.PLATFORM_ERROR);
        }
    }

    @Override
    public void unloadLibrary() {
    }

    @Override
    public long createWindow(String title, int width, int height, long flags) {
        System.out.println("[MinecraftWeb WebGPU] Creating SDL window: " + title + " " + width + "x" + height);
        try {
            long window = SDLVideo.SDL_CreateWindow(title, width, height, flags);
            System.out.println("[MinecraftWeb WebGPU] SDL_CreateWindow returned handle=" + window);
            if (window == 0L) {
                throw new IllegalStateException("SDL_CreateWindow returned a null/zero window handle");
            }
            return window;
        } catch (Throwable failure) {
            System.err.println("[MinecraftWeb WebGPU] Window creation failed: " + failure);
            failure.printStackTrace(System.err);
            throw new IllegalStateException("WebGPU backend could not create the browser SDL window", failure);
        }
    }

    @Override
    public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
        if (!WG.available()) {
            throw new BackendCreationException("WebGPU device was not created", BackendCreationException.Reason.PLATFORM_ERROR);
        }
        return new FrontendGpuDevice(new WebGpuDevice());
    }
}
