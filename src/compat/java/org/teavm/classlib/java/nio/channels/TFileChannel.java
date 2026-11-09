package org.teavm.classlib.java.nio.channels;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.teavm.classlib.java.io.TIOException;
import org.teavm.classlib.java.nio.TByteBuffer;
import org.teavm.classlib.java.nio.file.TOpenOption;
import org.teavm.classlib.java.nio.file.TPath;
import org.teavm.classlib.java.nio.file.TStandardOpenOption;
import org.teavm.classlib.java.nio.file.attribute.TFileAttribute;
import mcport.rt.ClasspathResources;

/**
 * TeaVM class-library extension for {@code java.nio.channels.FileChannel}.
 *
 * <p>TeaVM 0.16 ships no FileChannel at all. Minecraft's session lock, event/telemetry log and
 * region files all open channels through {@code FileChannel.open}, so the browser port needs a
 * working implementation. The browser has no host disk: reads fall back to the client-jar
 * resource archive (what {@code window.__mcResources} loaded), writes live in a session map.
 * This is enough for options, logs, crash reports and the session lock; real world storage is
 * provided later by the port's virtual file systems.</p>
 */
public class TFileChannel {

    private static final Map<String, byte[]> FILES = new HashMap<>();

    private byte[] data;
    private int size;
    private int position;
    private final boolean writable;
    private final String key;
    private boolean open = true;

    protected TFileChannel() {
        this.data = new byte[0];
        this.key = "";
        this.writable = false;
    }

    private TFileChannel(String key, byte[] initial, boolean writable) {
        this.key = key;
        this.data = initial;
        this.size = initial.length;
        this.writable = writable;
    }

    // ---- open -----------------------------------------------------------------------------

    public static TFileChannel open(TPath path, TOpenOption... options) throws TIOException {
        return open0(path, options);
    }

    public static TFileChannel open(TPath path, Set<? extends TOpenOption> options,
            TFileAttribute<?>... attrs) throws TIOException {
        return open0(path, options.toArray(new TOpenOption[0]));
    }

    private static TFileChannel open0(TPath path, TOpenOption[] options) throws TIOException {
        String key = normalize(path.toString());
        boolean write = false;
        boolean create = false;
        boolean createNew = false;
        boolean append = false;
        boolean truncate = false;
        for (TOpenOption option : options) {
            if (option == TStandardOpenOption.WRITE) {
                write = true;
            } else if (option == TStandardOpenOption.CREATE) {
                create = true;
            } else if (option == TStandardOpenOption.CREATE_NEW) {
                create = true;
                createNew = true;
            } else if (option == TStandardOpenOption.APPEND) {
                write = true;
                append = true;
            } else if (option == TStandardOpenOption.TRUNCATE_EXISTING) {
                write = true;
                truncate = true;
            }
        }
        byte[] existing = FILES.get(key);
        if (existing == null) {
            existing = resource(key);
        }
        if (existing != null && createNew && FILES.containsKey(key)) {
            throw new TIOException("File already exists: " + path);
        }
        if (existing == null) {
            existing = new byte[0];
        } else {
            byte[] copy = new byte[existing.length];
            System.arraycopy(existing, 0, copy, 0, existing.length);
            existing = copy;
        }
        TFileChannel channel = new TFileChannel(key, existing, write);
        if (truncate) {
            channel.size = 0;
        }
        if (append) {
            channel.position = channel.size;
        }
        if (create) {
            channel.stored = true;
        }
        return channel;
    }

    private boolean stored;

    private static byte[] resource(String key) {
        byte[] data = ClasspathResources.bytes(key);
        if (data == null && key.startsWith("minecraft/")) {
            data = ClasspathResources.bytes(key.substring("minecraft/".length()));
        }
        return data;
    }

    private static String normalize(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        boolean slash = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\' || c == '/') {
                if (!slash) {
                    sb.append('/');
                    slash = true;
                }
            } else {
                sb.append(c);
                slash = false;
            }
        }
        String s = sb.toString();
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    // ---- channel API ----------------------------------------------------------------------

    public int read(TByteBuffer dst) throws TIOException {
        if (!open) {
            throw new TIOException("Channel closed");
        }
        if (position >= size) {
            return -1;
        }
        int n = Math.min(dst.remaining(), size - position);
        for (int i = 0; i < n; i++) {
            dst.put(data[position + i]);
        }
        position += n;
        return n;
    }

    public int read(TByteBuffer dst, long at) throws TIOException {
        if (!open) {
            throw new TIOException("Channel closed");
        }
        if (at >= size) {
            return -1;
        }
        int start = (int) at;
        int n = Math.min(dst.remaining(), size - start);
        for (int i = 0; i < n; i++) {
            dst.put(data[start + i]);
        }
        return n;
    }

    public int write(TByteBuffer src) throws TIOException {
        if (!open) {
            throw new TIOException("Channel closed");
        }
        if (!writable) {
            throw new TIOException("Channel not open for writing");
        }
        int n = src.remaining();
        ensure(position + n);
        for (int i = 0; i < n; i++) {
            data[position + i] = src.get();
        }
        position += n;
        if (position > size) {
            size = position;
        }
        stored = true;
        return n;
    }

    public int write(TByteBuffer src, long at) throws TIOException {
        if (!open) {
            throw new TIOException("Channel closed");
        }
        if (!writable) {
            throw new TIOException("Channel not open for writing");
        }
        int start = (int) at;
        int n = src.remaining();
        ensure(start + n);
        for (int i = 0; i < n; i++) {
            data[start + i] = src.get();
        }
        if (start + n > size) {
            size = start + n;
        }
        stored = true;
        return n;
    }

    private void ensure(int capacity) {
        if (capacity <= data.length) {
            return;
        }
        int n = Math.max(capacity, Math.max(64, data.length * 2));
        byte[] grown = new byte[n];
        System.arraycopy(data, 0, grown, 0, size);
        data = grown;
    }

    public long position() throws TIOException {
        return position;
    }

    public TFileChannel position(long newPosition) throws TIOException {
        if (newPosition < 0) {
            throw new TIOException("Negative position");
        }
        this.position = (int) newPosition;
        return this;
    }

    public long size() throws TIOException {
        return size;
    }

    public TFileChannel truncate(long newSize) throws TIOException {
        if (newSize < 0) {
            throw new TIOException("Negative size");
        }
        if (newSize < size) {
            size = (int) newSize;
            if (position > size) {
                position = size;
            }
            stored = true;
        }
        return this;
    }

    public void force(boolean metaData) throws TIOException {
    }

    public boolean isOpen() {
        return open;
    }

    public void close() throws TIOException {
        if (!open) {
            return;
        }
        open = false;
        if (writable && stored) {
            byte[] out = new byte[size];
            System.arraycopy(data, 0, out, 0, size);
            FILES.put(key, out);
        }
    }

    public TFileLock tryLock() throws TIOException {
        return new TFileLock(this, 0, Long.MAX_VALUE, false);
    }

    public TFileLock tryLock(long position, long size, boolean shared) throws TIOException {
        return new TFileLock(this, position, size, shared);
    }

    public TFileLock lock() throws TIOException {
        return tryLock();
    }

    public TFileLock lock(long position, long size, boolean shared) throws TIOException {
        return new TFileLock(this, position, size, shared);
    }
}
