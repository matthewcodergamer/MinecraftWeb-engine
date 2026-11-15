package mcport.rt.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.packs.linkfs.LinkFSProvider;

/**
 * TeaVM-safe NIO stream/channel dispatch for browser-backed LinkFS paths.
 *
 * TeaVM does not reliably emit abstract FileSystemProvider dispatch for these
 * methods. Call the concrete LinkFSProvider implementation directly, and log
 * any other provider before falling back to normal provider dispatch.
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
        return Channels.newInputStream(openChannel(provider, path, openOptions));
    }

    /**
     * Redirect target for Files.newByteChannel(Path, Set, FileAttribute...).
     * The path's own filesystem determines the provider, not the provider of
     * the LinkFS path that initiated the delegation.
     * Resolve the provider from the actual target path: LinkFS may delegate to
     * a different provider when it follows a link.
     */
    public static SeekableByteChannel newByteChannel(
            Path path, Set<OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        FileSystemProvider provider = path.getFileSystem().provider();
        return openChannel(provider, path, options, attrs);
    }

    private static SeekableByteChannel openChannel(
            FileSystemProvider provider, Path path, Set<OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {
        if (provider instanceof LinkFSProvider) {
            return ((LinkFSProvider) provider).newByteChannel(path, options, attrs);
        }
        System.err.println("[mcport.fs] newByteChannel fallback provider="
                + provider.getClass().getName() + " path=" + path);
        return provider.newByteChannel(path, options, attrs);
    }
}
