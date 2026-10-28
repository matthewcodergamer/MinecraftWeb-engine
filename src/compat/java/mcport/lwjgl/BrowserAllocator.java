package mcport.lwjgl;

import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSBody;

/** LWJGL's native allocator (malloc/calloc/realloc/free) backed by the JS heap allocator in web/native/heap.js. */
public final class BrowserAllocator implements MemoryUtil.MemoryAllocator {
    public static final BrowserAllocator INSTANCE = new BrowserAllocator();

    private BrowserAllocator() {
    }

    public static MemoryUtil.MemoryAllocator getInstance() {
        return INSTANCE;
    }

    @JSBody(params = {"n", "align"}, script = "return __mcHeap.malloc(n, align);")
    private static native double jsMalloc(double n, int align);

    @JSBody(params = {"n"}, script = "return __mcHeap.calloc(n);")
    private static native double jsCalloc(double n);

    @JSBody(params = {"p", "n"}, script = "return __mcHeap.realloc(p, n);")
    private static native double jsRealloc(double p, double n);

    @JSBody(params = {"p"}, script = "__mcHeap.free(p);")
    private static native void jsFree(double p);

    @JSBody(params = {"name"}, script = "return __mcNative.lookup('libc', name);")
    private static native int fn(String name);

    static long malloc0(long size) {
        return (long) jsMalloc((double) size, 16);
    }

    static void free0(long p) {
        jsFree((double) p);
    }

    @Override public long getMalloc() { return fn("malloc"); }
    @Override public long getCalloc() { return fn("calloc"); }
    @Override public long getRealloc() { return fn("realloc"); }
    @Override public long getFree() { return fn("free"); }
    @Override public long getAlignedAlloc() { return fn("aligned_alloc"); }
    @Override public long getAlignedFree() { return fn("free"); }

    @Override public long malloc(long size) { return (long) jsMalloc((double) size, 16); }
    @Override public long calloc(long num, long size) { return (long) jsCalloc((double) (num * size)); }
    @Override public long realloc(long ptr, long size) { return (long) jsRealloc((double) ptr, (double) size); }
    @Override public void free(long ptr) { jsFree((double) ptr); }
    @Override public long aligned_alloc(long alignment, long size) { return (long) jsMalloc((double) size, (int) alignment); }
    @Override public void aligned_free(long ptr) { jsFree((double) ptr); }

    /** java.nio.ByteBuffer.allocateDirect: direct buffers must live in the native heap so LWJGL can address them. */
    public static java.nio.ByteBuffer allocateDirect(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: " + capacity);
        }
        // Never freed: the JDK frees these via a Cleaner; the client allocates few, long-lived ones.
        long p = (long) jsCalloc(Math.max(capacity, 1));
        return BrowserMemoryBackend.wrap(p, capacity).order(java.nio.ByteOrder.BIG_ENDIAN);
    }
}
