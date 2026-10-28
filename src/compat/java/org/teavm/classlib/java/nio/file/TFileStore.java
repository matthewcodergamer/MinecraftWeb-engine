package org.teavm.classlib.java.nio.file;

import java.io.IOException;
import org.teavm.classlib.java.nio.file.attribute.TFileAttributeView;
import org.teavm.classlib.java.nio.file.attribute.TFileStoreAttributeView;

/**
 * TeaVM class-library extension for {@code java.nio.file.FileStore}.
 *
 * <p>TeaVM 0.16 ships no FileStore, but Minecraft's virtual file systems extend it. Browser file
 * stores are synthetic: the game cannot see a host disk, so space queries are answered by
 * {@code mcport.rt.fs.BrowserFileStore} and archive/link stores keep their own trivial values.</p>
 */
public abstract class TFileStore {
    protected TFileStore() {
    }

    public abstract String name();

    public abstract String type();

    public abstract boolean isReadOnly();

    public abstract long getTotalSpace() throws IOException;

    public abstract long getUsableSpace() throws IOException;

    public abstract long getUnallocatedSpace() throws IOException;

    public long getBlockSize() throws IOException {
        return 4096L;
    }

    public abstract boolean supportsFileAttributeView(Class<? extends TFileAttributeView> type);

    public abstract boolean supportsFileAttributeView(String name);

    public abstract <V extends TFileStoreAttributeView> V getFileStoreAttributeView(Class<V> type);

    public abstract Object getAttribute(String attribute) throws IOException;
}
