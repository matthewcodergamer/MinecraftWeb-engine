package mcport.rt.patch;

import java.nio.ByteBuffer;
import mcport.lwjgl.BrowserAllocator;
import mcport.lwjgl.BrowserMemoryBackend;
import java.nio.NativeHeapBuffers;

/** Browser-safe replacements for Netty PlatformDependent0 operations that use native pointers or MethodHandles. */
public final class NettyBrowserPatches {
    private NettyBrowserPatches() {}

    public static void copyMemory(long src, long dst, long bytes) {
        BrowserMemoryBackend.copyMemory(src, dst, bytes);
    }

    public static void copyMemoryWithSafePointPolling(long src, long dst, long bytes) {
        // The browser event loop cannot be interrupted by JVM safepoints. The heap copy is bounds checked.
        BrowserMemoryBackend.copyMemory(src, dst, bytes);
    }

    public static ByteBuffer allocateDirectNoCleaner(int capacity) {
        return BrowserAllocator.allocateDirect(capacity);
    }

    public static ByteBuffer reallocateDirectNoCleaner(ByteBuffer oldBuffer, int capacity) {
        if (capacity < 0) throw new IllegalArgumentException("capacity < 0: " + capacity);
        ByteBuffer replacement = BrowserAllocator.allocateDirect(capacity);
        ByteBuffer source = oldBuffer.duplicate();
        source.clear();
        source.limit(Math.min(oldBuffer.capacity(), capacity));
        replacement.put(source);
        replacement.clear();
        return replacement;
    }

    public static ByteBuffer alignSlice(ByteBuffer buffer, int alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("alignment must be a positive power of two");
        }
        int base = NativeHeapBuffers.baseAddress(buffer);
        if (base < 0) throw new IllegalArgumentException("buffer is not backed by browser native heap");
        int start = buffer.position();
        int limit = buffer.limit();
        start += (alignment - ((base + start) & (alignment - 1))) & (alignment - 1);
        limit -= (base + limit) & (alignment - 1);
        if (limit < start) limit = start;
        ByteBuffer view = buffer.duplicate();
        view.position(start);
        view.limit(limit);
        return view.slice().order(buffer.order());
    }

    public static ByteBuffer offsetSlice(ByteBuffer buffer, int index, int length) {
        if (index < 0 || length < 0 || index > buffer.limit() - length) {
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer view = buffer.duplicate();
        view.position(index);
        view.limit(index + length);
        return view.slice().order(buffer.order());
    }

    public static ByteBuffer absolutePut(ByteBuffer dst, int dstOffset, ByteBuffer src, int srcOffset, int length) {
        if (dstOffset < 0 || srcOffset < 0 || length < 0
                || dstOffset > dst.limit() - length || srcOffset > src.limit() - length) {
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer source = src.duplicate();
        source.position(srcOffset);
        source.limit(srcOffset + length);
        ByteBuffer target = dst.duplicate();
        target.position(dstOffset);
        target.put(source);
        return dst;
    }

    public static ByteBuffer absolutePut(ByteBuffer dst, int dstOffset, byte[] src, int srcOffset, int length) {
        if (dstOffset < 0 || srcOffset < 0 || length < 0
                || dstOffset > dst.limit() - length || srcOffset > src.length - length) {
            throw new IndexOutOfBoundsException();
        }
        ByteBuffer target = dst.duplicate();
        target.position(dstOffset);
        target.put(src, srcOffset, length);
        return dst;
    }

    public static byte[] allocateUninitializedArray(int size) {
        if (size < 0) throw new NegativeArraySizeException();
        return new byte[size];
    }

    public static boolean available() { return true; }
}
