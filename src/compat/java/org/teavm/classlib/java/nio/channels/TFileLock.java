package org.teavm.classlib.java.nio.channels;

import org.teavm.classlib.java.io.TIOException;

/**
 * TeaVM class-library extension for {@code java.nio.channels.FileLock}.
 *
 * <p>Browser sessions are single-process, so every requested lock succeeds. Minecraft only keeps
 * the lock valid for the lifetime of its {@code DirectoryLock} / event log and never contends it.
 * The class is concrete so the port can return a real object instead of {@code null} (which
 * {@code DirectoryLock} treats as "another process owns the world").</p>
 */
public class TFileLock {

    private final TFileChannel channel;
    private final long position;
    private final long size;
    private final boolean shared;
    private boolean valid = true;

    protected TFileLock(TFileChannel channel, long position, long size, boolean shared) {
        this.channel = channel;
        this.position = position;
        this.size = size;
        this.shared = shared;
    }

    public final TFileChannel channel() {
        return channel;
    }

    public final long position() {
        return position;
    }

    public final long size() {
        return size;
    }

    public final boolean isShared() {
        return shared;
    }

    public final boolean overlaps(long position, long size) {
        return position + size > this.position && position < this.position + this.size;
    }

    public final boolean isValid() {
        return valid;
    }

    public final void release() throws TIOException {
        valid = false;
    }

    public final void close() throws TIOException {
        release();
    }

    public final String toString() {
        return "FileLock[" + position + ":" + size + (shared ? " shared" : " exclusive") + "]";
    }
}
