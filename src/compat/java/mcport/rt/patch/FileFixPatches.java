package mcport.rt.patch;

import java.nio.file.Path;
import net.minecraft.util.filefix.FileSystemCapabilities;

/** Browser-safe file-system capability fallbacks for the legacy world-upgrade path. */
public final class FileFixPatches {
    private FileFixPatches() {
    }

    public static FileSystemCapabilities detectFileSystemCapabilities(Path ignored) {
        return new FileSystemCapabilities(false, false);
    }

    public static boolean supportsAtomicMove(Path ignored) {
        return false;
    }

    public static boolean supportsHardLinks(Path ignored) {
        return false;
    }
}
