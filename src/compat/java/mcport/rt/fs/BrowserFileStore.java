package mcport.rt.fs;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

/**
 * A browser FileStore for callers that ask {@code Files.getFileStore(Path)} (Minecraft's disk-space
 * probing and crash reports). There is no host disk in the browser; report a large writable store.
 */
public final class BrowserFileStore extends FileStore {
    private static final BrowserFileStore INSTANCE = new BrowserFileStore();
    private static final long SPACE = 1L << 40; // 1 TiB

    private BrowserFileStore() {
    }

    public static FileStore getFileStore(Path path) {
        return INSTANCE;
    }

    @Override
    public String name() {
        return "browser";
    }

    @Override
    public String type() {
        return "web";
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public long getTotalSpace() {
        return SPACE;
    }

    @Override
    public long getUsableSpace() {
        return SPACE;
    }

    @Override
    public long getUnallocatedSpace() {
        return SPACE;
    }

    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
        return false;
    }

    @Override
    public boolean supportsFileAttributeView(String name) {
        return false;
    }

    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
        return null;
    }

    @Override
    public Object getAttribute(String attribute) throws IOException {
        throw new UnsupportedOperationException(attribute);
    }
}
