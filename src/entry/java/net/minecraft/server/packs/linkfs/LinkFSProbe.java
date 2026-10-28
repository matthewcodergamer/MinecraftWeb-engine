package net.minecraft.server.packs.linkfs;

import java.nio.file.FileStore;

/** Package-private access to LinkFSFileStore for the build-time FileStore probe. */
public final class LinkFSProbe {
    private LinkFSProbe() {
    }

    public static FileStore make() {
        return new LinkFSFileStore("probe");
    }
}
