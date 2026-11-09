package java.nio.file;

import java.io.IOException;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

/**
 * Compile-time signature of {@code java.nio.file.FileStore} for the browser port.
 *
 * <p>TeaVM 0.16.0 does not ship a class-library implementation of {@code FileStore}. Minecraft's
 * virtual file systems (the client-jar archive and {@code linkfs}) still extend it, so the JDK
 * compiler needs the type to exist while javac builds {@code build/classes}. This source is
 * compiled with {@code --patch-module java.base=src/compat/java-stubs} into {@code build/stubs}
 * and is therefore <b>never</b> part of the TeaVM runtime: TeaVM substitutes the real calls with
 * {@code org.teavm.classlib.java.nio.file.TFileStore} and the port's
 * {@code mcport.rt.fs.BrowserFileStore}.</p>
 *
 * <p>Keep these signatures in lock-step with JDK 25. They are the compile contract only; the
 * methods throw at runtime if this class is ever accidentally shipped.</p>
 */
public abstract class FileStore {
    protected FileStore() {
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

    public abstract boolean supportsFileAttributeView(Class<? extends FileAttributeView> type);

    public abstract boolean supportsFileAttributeView(String name);

    public abstract <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type);

    public abstract Object getAttribute(String attribute) throws IOException;
}
