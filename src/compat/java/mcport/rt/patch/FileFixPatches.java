package mcport.rt.patch;

import java.nio.file.Path;
import net.minecraft.util.filefix.FileSystemCapabilities;

/**
 * Browser-safe replacement for FileFixerUpper.detectFileSystemCapabilities.
 *
 * The browser port must not claim support for atomic moves or hard links:
 * those capabilities are not provided by the browser filesystem abstraction.
 * Fresh worlds do not enter the file-fixing/swap path. If an old world does
 * require that path, reporting both capabilities as unavailable makes the
 * existing code fail closed with AtomicMoveNotSupportedFileFixException
 * rather than attempting an unsafe hard-link or atomic-move operation.
 */
public final class FileFixPatches {
    private FileFixPatches() {
    }

    public static FileSystemCapabilities detectFileSystemCapabilities(Path ignored) {
        return new FileSystemCapabilities(false, false);
    }
}
