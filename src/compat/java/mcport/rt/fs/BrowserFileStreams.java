package mcport.rt.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * TeaVM-safe implementation of FileSystemProvider.newInputStream.
 *
 * Some TeaVM-translated provider dispatch paths enter the JDK default
 * FileSystemProvider.newInputStream implementation and fail before reaching
 * the provider's newByteChannel implementation. Calling newByteChannel
 * directly on the provider preserves provider-specific LinkFS delegation
 * while avoiding the default newInputStream route.
 */
public final class BrowserFileStreams {
    private BrowserFileStreams() {
    }

    public static InputStream newInputStream(
            FileSystemProvider provider, Path path, OpenOption... options) throws IOException {
        Set<OpenOption> openOptions = new HashSet<>(options.length == 0 ? 1 : options.length);
        if (options.length == 0) {
            openOptions.add(StandardOpenOption.READ);
        } else {
            for (OpenOption option : options) {
                if (option != StandardOpenOption.READ) {
                    throw new UnsupportedOperationException("Only READ is supported: " + option);
                }
                openOptions.add(option);
            }
        }
        System.out.println("[mcport.fs] newInputStream provider=" + provider.getClass().getName()
                + " path=" + path);
        return Channels.newInputStream(provider.newByteChannel(path, openOptions));
    }
}
