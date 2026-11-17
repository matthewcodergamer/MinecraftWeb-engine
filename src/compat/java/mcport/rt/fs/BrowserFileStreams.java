package mcport.rt.fs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashSet;
import java.util.Set;

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
        // Do not log from this low-level stream shim: stdout is routed back
        // through Minecraft's logger and can recurse while a resource is opened.
        return asInputStream(openChannel(provider, path, openOptions));
    }

    /**
     * Avoid java.nio.channels.Channels: TeaVM 0.16 does not provide that JDK
     * utility class. This adapter implements the only operation required by
     * the browser VFS here, converting the already-open channel to an input
     * stream without introducing another class-library dependency.
     */
    private static InputStream asInputStream(SeekableByteChannel channel) {
        return new InputStream() {
            private boolean closed;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int count = read(one, 0, 1);
                return count < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (closed) {
                    throw new IOException("Stream is closed");
                }
                if (bytes == null) {
                    throw new NullPointerException("bytes");
                }
                if (offset < 0 || length < 0 || length > bytes.length - offset) {
                    throw new IndexOutOfBoundsException();
                }
                if (length == 0) {
                    return 0;
                }
                return channel.read(java.nio.ByteBuffer.wrap(bytes, offset, length));
            }

            @Override
            public int available() throws IOException {
                if (closed) {
                    throw new IOException("Stream is closed");
                }
                long remaining = Math.max(0L, channel.size() - channel.position());
                return (int) Math.min(Integer.MAX_VALUE, remaining);
            }

            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    channel.close();
                }
            }
        };
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
        return net.minecraft.server.packs.linkfs.BrowserLinkFSAccess.newByteChannel(
                provider, path, options, attrs);
    }
}
