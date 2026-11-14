package mcport.rt;

import com.mojang.blaze3d.platform.IconSet;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.server.packs.PackMetadataResources;

/** Browser-specific replacements for desktop window operations. */
public final class WindowBrowser {
    private WindowBrowser() {
    }

    /** Browsers have no desktop window icon; avoid the unsupported filesystem lookup. */
    public static void noopIcon(Window window, PackMetadataResources resources, IconSet iconSet) {
        // Intentionally empty.
    }
}
