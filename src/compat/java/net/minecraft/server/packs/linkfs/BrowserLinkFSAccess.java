package net.minecraft.server.packs.linkfs;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.spi.FileSystemProvider;
import java.util.Set;

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
        System.err.println("[mcport.fs] newByteChannel fallback provider="
                + provider.getClass().getName() + " path=" + path);
        return provider.newByteChannel(path, options, attrs);
    }
}
