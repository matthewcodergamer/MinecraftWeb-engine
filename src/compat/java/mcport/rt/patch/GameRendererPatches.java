package mcport.rt.patch;

import net.minecraft.client.renderer.GameRenderer;

/**
 * Browser-safe replacements for client rendering paths that require a writable filesystem.
 */
public final class GameRendererPatches {
    private GameRendererPatches() {
    }

    /**
     * The browser VFS is read-only, so the automatic world-icon screenshot would throw a
     * RuntimeException from NativeImage.writeToFile on an IO worker and crash the game.
     * The icon is only cosmetic in the save list, so do not attempt the screenshot.
     */
    public static void tryTakeScreenshotIfNeeded(GameRenderer renderer) {
        // Intentionally empty.
    }
}
