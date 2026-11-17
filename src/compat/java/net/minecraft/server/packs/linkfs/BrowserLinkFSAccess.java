package net.minecraft.server.packs.linkfs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.spi.FileSystemProvider;
import java.util.Set;
import org.teavm.classlib.java.nio.file.TStandardOpenOption;
import org.teavm.classlib.java.nio.file.impl.TDefaultFileSystem;
import org.teavm.classlib.java.nio.file.impl.TDefaultFileSystemProvider;

/**
 * Public compatibility bridge for TeaVM's missing abstract provider dispatch.
 * This class shares LinkFSProvider's package so it can legally call the
 * package-private provider's concrete newByteChannel implementation.
 */
public final class BrowserLinkFSAccess {
    private BrowserLinkFSAccess() {
    }

    public static SeekableByteChannel newByteChannel(
            FileSystemProvider provider, Path path, Set<OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {
        if (provider instanceof LinkFSProvider) {
            return ((LinkFSProvider) provider).newByteChannel(path, options, attrs);
        }
        if (provider instanceof mcport.rt.fs.JarFileSystemProvider) {
            return ((mcport.rt.fs.JarFileSystemProvider) provider).newByteChannel(path, options, attrs);
        }

        // TeaVM 0.16's TFileSystemProvider deliberately exposes stream methods,
        // but no newByteChannel method. Calling the Java facade's abstract
        // FileSystemProvider method here produces NoSuchMethodError. Adapt the
        // default VFS provider's stream into a read-only seekable channel instead.
        if (path.getFileSystem().provider() == provider) {
            return defaultFileChannel(path, options, attrs);
        }

        throw new UnsupportedOperationException("No browser byte-channel adapter for "
                + provider.getClass().getName() + " path=" + path);
    }

    private static SeekableByteChannel defaultFileChannel(
            Path path, Set<OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        if (attrs.length != 0) {
            throw new UnsupportedOperationException("File attributes are not supported by the browser VFS");
        }
        for (OpenOption option : options) {
            if (option != StandardOpenOption.READ) {
                throw new ReadOnlyFileSystemException();
            }
        }

        byte[] bytes;
        try (InputStream input = TDefaultFileSystemProvider.INSTANCE.newInputStream(
                TDefaultFileSystem.INSTANCE.getPath(path.toString()), TStandardOpenOption.READ);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }

        return new SeekableByteChannel() {
            private int position;
            private boolean open = true;

            private void ensureOpen() throws ClosedChannelException {
                if (!open) {
                    throw new ClosedChannelException();
                }
            }

            @Override
            public int read(ByteBuffer destination) throws IOException {
                ensureOpen();
                if (position >= bytes.length) {
                    return -1;
                }
                int count = Math.min(destination.remaining(), bytes.length - position);
                destination.put(bytes, position, count);
                position += count;
                return count;
            }

            @Override
            public int write(ByteBuffer source) {
                throw new ReadOnlyFileSystemException();
            }

            @Override
            public long position() throws IOException {
                ensureOpen();
                return position;
            }

            @Override
            public SeekableByteChannel position(long value) throws IOException {
                ensureOpen();
                if (value < 0) {
                    throw new IllegalArgumentException("negative position");
                }
                position = (int) Math.min(value, bytes.length);
                return this;
            }

            @Override
            public long size() throws IOException {
                ensureOpen();
                return bytes.length;
            }

            @Override
            public SeekableByteChannel truncate(long size) {
                throw new ReadOnlyFileSystemException();
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() {
                open = false;
            }
        };
    }
}
