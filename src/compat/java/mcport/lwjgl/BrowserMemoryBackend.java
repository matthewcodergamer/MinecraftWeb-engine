package mcport.lwjgl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.NativeHeapBuffers;
import java.nio.ShortBuffer;
import org.lwjgl.system.MemoryBackend;
import org.lwjgl.system.MemoryUtil;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSByRef;

/**
 * LWJGL MemoryBackend over the emulated native heap (web/native/heap.js): a single ArrayBuffer in which
 * a native address is a byte offset. Replaces LWJGL's sun.misc.Unsafe / FFM backends; MemoryUtil,
 * MemoryStack, PointerBuffer and every LWJGL struct class run unmodified on top of it.
 * Layout is LP64 little-endian (8-byte pointers and C longs), matching what the JS natives expect.
 */
public final class BrowserMemoryBackend implements MemoryBackend {
    public static final BrowserMemoryBackend INSTANCE = new BrowserMemoryBackend();

    private BrowserMemoryBackend() {
    }

    public static MemoryBackend create() {
        return INSTANCE;
    }

    // ---- raw heap access (DataView, little-endian; unaligned access is always allowed) ----

    @JSBody(params = "a", script = "return __mcHeap.u8[a];")
    static native int u8(int a);

    @JSBody(params = "a", script = "return __mcHeap.dv.getInt8(a);")
    static native byte i8(int a);

    @JSBody(params = "a", script = "return __mcHeap.dv.getInt16(a, true);")
    static native short i16(int a);

    @JSBody(params = "a", script = "return __mcHeap.dv.getUint16(a, true);")
    static native char u16(int a);

    @JSBody(params = "a", script = "return __mcHeap.dv.getInt32(a, true);")
    static native int i32(int a);

    @JSBody(params = "a", script = "return __mcHeap.dv.getFloat32(a, true);")
    static native float f32(int a);

    @JSBody(params = "a", script = "return __mcHeap.dv.getFloat64(a, true);")
    static native double f64(int a);

    @JSBody(params = {"a", "v"}, script = "__mcHeap.dv.setInt8(a, v);")
    static native void put8(int a, int v);

    @JSBody(params = {"a", "v"}, script = "__mcHeap.dv.setInt16(a, v, true);")
    static native void put16(int a, int v);

    @JSBody(params = {"a", "v"}, script = "__mcHeap.dv.setInt32(a, v, true);")
    static native void put32(int a, int v);

    @JSBody(params = {"a", "v"}, script = "__mcHeap.dv.setFloat32(a, v, true);")
    static native void putF32(int a, float v);

    @JSBody(params = {"a", "v"}, script = "__mcHeap.dv.setFloat64(a, v, true);")
    static native void putF64(int a, double v);

    @JSBody(params = {"a", "v", "n"}, script = "__mcHeap.u8.fill(v, a, a + n);")
    static native void fill(int a, int v, int n);

    @JSBody(params = {"src", "dst", "n"}, script = "__mcHeap.u8.copyWithin(dst, src, src + n);")
    static native void move(int src, int dst, int n);

    /** Copies n bytes of a typed array view (byte offset off) into the heap at dst. */
    @JSBody(params = {"arr", "off", "dst", "n"}, script =
            "__mcHeap.u8.set(new Uint8Array(arr.buffer, arr.byteOffset + off, n), dst);")
    private static native void fromArray(org.teavm.jso.JSObject arr, int off, int dst, int n);

    /** Copies n bytes from the heap at src into a typed array view at byte offset off. */
    @JSBody(params = {"src", "arr", "off", "n"}, script =
            "new Uint8Array(arr.buffer, arr.byteOffset + off, n).set(__mcHeap.u8.subarray(src, src + n));")
    private static native void toArray(int src, org.teavm.jso.JSObject arr, int off, int n);

    @JSBody(params = "a", script = "return a;")
    private static native org.teavm.jso.JSObject b(@JSByRef byte[] a);

    @JSBody(params = "a", script = "return a;")
    private static native org.teavm.jso.JSObject c(@JSByRef char[] a);

    @JSBody(params = "a", script = "return a;")
    private static native org.teavm.jso.JSObject s(@JSByRef short[] a);

    @JSBody(params = "a", script = "return a;")
    private static native org.teavm.jso.JSObject i(@JSByRef int[] a);

    @JSBody(params = "a", script = "return a;")
    private static native org.teavm.jso.JSObject l(@JSByRef long[] a);

    @JSBody(params = "a", script = "return a;")
    private static native org.teavm.jso.JSObject f(@JSByRef float[] a);

    @JSBody(params = "a", script = "return a;")
    private static native org.teavm.jso.JSObject d(@JSByRef double[] a);

    @JSBody(params = {"a", "n"}, script = "return __mcHeap.utf8.decode(__mcHeap.u8.subarray(a, a + n));")
    private static native String decodeUtf8(int a, int n);

    @JSBody(params = {"a", "n"}, script =
            "var s = ''; for (var i = 0; i < n; i++) s += String.fromCharCode(__mcHeap.u8[a + i]); return s;")
    private static native String decodeAscii(int a, int n);

    @JSBody(params = {"a", "n"}, script = "return String.fromCharCode.apply(null, new Uint16Array(__mcHeap.buffer.slice(a, a + 2 * n)));")
    private static native String decodeUtf16(int a, int n);

    @JSBody(params = "a", script = "var u = __mcHeap.u8, e = a; while (u[e] !== 0) e++; return e - a;")
    static native int strlen(int a);

    @JSBody(params = "a", script = "var d = __mcHeap.dv, e = a; while (d.getUint16(e, true) !== 0) e += 2; return (e - a) >> 1;")
    private static native int strlen16(int a);

    /** Encodes s as UTF-8 at a (no terminator); returns the number of bytes written. */
    @JSBody(params = {"s", "a"}, script = "return __mcHeap.utf8e.encodeInto(s, __mcHeap.u8.subarray(a)).written;")
    private static native int encodeUtf8(String s, int a);

    @JSBody(params = "s", script = "return __mcHeap.utf8e.encode(s).length;")
    private static native int utf8Length(String s);

    // ---- helpers ----

    static int a(long address) {
        return (int) address;
    }

    static long getLong0(int a) {
        return (i32(a) & 0xFFFFFFFFL) | ((long) i32(a + 4) << 32);
    }

    static void putLong0(int a, long v) {
        put32(a, (int) v);
        put32(a + 4, (int) (v >>> 32));
    }

    private static int base(Buffer buffer) {
        int base = NativeHeapBuffers.baseAddress(buffer);
        if (base < 0) {
            throw new IllegalArgumentException("buffer is not in the native heap: " + buffer
                    + " (allocate it with MemoryUtil/MemoryStack or ByteBuffer.allocateDirect)");
        }
        return base;
    }

    private static int addr(Buffer buffer, int index) {
        return base(buffer) + index * NativeHeapBuffers.elementSize(buffer);
    }

    // ---- MemoryBackend: scalar access ----

    @Override public boolean getBoolean(long address) { return u8(a(address)) != 0; }
    @Override public byte getByte(long address) { return i8(a(address)); }
    @Override public char getChar(long address) { return u16(a(address)); }
    @Override public char getCharUnaligned(long address) { return u16(a(address)); }
    @Override public short getShort(long address) { return i16(a(address)); }
    @Override public short getShortUnaligned(long address) { return i16(a(address)); }
    @Override public int getInt(long address) { return i32(a(address)); }
    @Override public int getIntUnaligned(long address) { return i32(a(address)); }
    @Override public long getLong(long address) { return getLong0(a(address)); }
    @Override public long getLongUnaligned(long address) { return getLong0(a(address)); }

    @Override
    public long getLong(byte[] array, long offset) {
        int o = (int) offset;
        long v = 0;
        for (int k = 7; k >= 0; k--) {
            v = (v << 8) | (array[o + k] & 0xFF);
        }
        return v;
    }

    @Override public float getFloat(long address) { return f32(a(address)); }
    @Override public float getFloatUnaligned(long address) { return f32(a(address)); }
    @Override public double getDouble(long address) { return f64(a(address)); }
    @Override public double getDoubleUnaligned(long address) { return f64(a(address)); }
    @Override public long getCLong(long address) { return getLong0(a(address)); }
    @Override public long getCLongUnaligned(long address) { return getLong0(a(address)); }
    @Override public long getAddress(long address) { return getLong0(a(address)); }
    @Override public long getAddressUnaligned(long address) { return getLong0(a(address)); }

    @Override public void putBoolean(long address, boolean v) { put8(a(address), v ? 1 : 0); }
    @Override public void putByte(long address, byte v) { put8(a(address), v); }
    @Override public void putChar(long address, char v) { put16(a(address), v); }
    @Override public void putCharUnaligned(long address, char v) { put16(a(address), v); }
    @Override public void putShort(long address, short v) { put16(a(address), v); }
    @Override public void putShortUnaligned(long address, short v) { put16(a(address), v); }
    @Override public void putInt(long address, int v) { put32(a(address), v); }
    @Override public void putIntUnaligned(long address, int v) { put32(a(address), v); }
    @Override public void putLong(long address, long v) { putLong0(a(address), v); }
    @Override public void putLongUnaligned(long address, long v) { putLong0(a(address), v); }
    @Override public void putFloat(long address, float v) { putF32(a(address), v); }
    @Override public void putFloatUnaligned(long address, float v) { putF32(a(address), v); }
    @Override public void putDouble(long address, double v) { putF64(a(address), v); }
    @Override public void putDoubleUnaligned(long address, double v) { putF64(a(address), v); }
    @Override public void putCLong(long address, long v) { putLong0(a(address), v); }
    @Override public void putCLongUnaligned(long address, long v) { putLong0(a(address), v); }
    @Override public void putAddress(long address, long v) { putLong0(a(address), v); }
    @Override public void putAddressUnaligned(long address, long v) { putLong0(a(address), v); }

    // ---- buffer addresses ----

    @Override public long getAddress0(Buffer b) { return base(b); }
    @Override public long getAddress0(ByteBuffer b) { return base(b); }
    @Override public long getAddress0(CharBuffer b) { return base(b); }
    @Override public long getAddress0(ShortBuffer b) { return base(b); }
    @Override public long getAddress0(IntBuffer b) { return base(b); }
    @Override public long getAddress0(LongBuffer b) { return base(b); }
    @Override public long getAddress0(FloatBuffer b) { return base(b); }
    @Override public long getAddress0(DoubleBuffer b) { return base(b); }

    @Override public long getAddress(Buffer b) { return addr(b, b.position()); }
    @Override public long getAddress(Buffer b, int position) { return addr(b, position); }
    @Override public long getAddress(ByteBuffer b) { return addr(b, b.position()); }
    @Override public long getAddress(ByteBuffer b, int position) { return addr(b, position); }
    @Override public long getAddress(CharBuffer b) { return addr(b, b.position()); }
    @Override public long getAddress(CharBuffer b, int position) { return addr(b, position); }
    @Override public long getAddress(ShortBuffer b) { return addr(b, b.position()); }
    @Override public long getAddress(ShortBuffer b, int position) { return addr(b, position); }
    @Override public long getAddress(IntBuffer b) { return addr(b, b.position()); }
    @Override public long getAddress(IntBuffer b, int position) { return addr(b, position); }
    @Override public long getAddress(LongBuffer b) { return addr(b, b.position()); }
    @Override public long getAddress(LongBuffer b, int position) { return addr(b, position); }
    @Override public long getAddress(FloatBuffer b) { return addr(b, b.position()); }
    @Override public long getAddress(FloatBuffer b, int position) { return addr(b, position); }
    @Override public long getAddress(DoubleBuffer b) { return addr(b, b.position()); }
    @Override public long getAddress(DoubleBuffer b, int position) { return addr(b, position); }

    // ---- buffers over native memory ----

    public static ByteBuffer wrap(long address, int capacityBytes) {
        return (ByteBuffer) NativeHeapBuffers.wrap(a(address), capacityBytes);
    }

    @Override public ByteBuffer wrapBufferByte(long address, int capacity) { return wrap(address, capacity); }
    @Override public CharBuffer wrapBufferChar(long address, int capacity) { return wrap(address, capacity * 2).asCharBuffer(); }
    @Override public ShortBuffer wrapBufferShort(long address, int capacity) { return wrap(address, capacity * 2).asShortBuffer(); }
    @Override public IntBuffer wrapBufferInt(long address, int capacity) { return wrap(address, capacity * 4).asIntBuffer(); }
    @Override public LongBuffer wrapBufferLong(long address, int capacity) { return wrap(address, capacity * 8).asLongBuffer(); }
    @Override public FloatBuffer wrapBufferFloat(long address, int capacity) { return wrap(address, capacity * 4).asFloatBuffer(); }
    @Override public DoubleBuffer wrapBufferDouble(long address, int capacity) { return wrap(address, capacity * 8).asDoubleBuffer(); }

    @Override public ByteBuffer duplicate(ByteBuffer b) { return b.duplicate().order(b.order()); }
    @Override public CharBuffer duplicate(CharBuffer b) { return b.duplicate(); }
    @Override public ShortBuffer duplicate(ShortBuffer b) { return b.duplicate(); }
    @Override public IntBuffer duplicate(IntBuffer b) { return b.duplicate(); }
    @Override public LongBuffer duplicate(LongBuffer b) { return b.duplicate(); }
    @Override public FloatBuffer duplicate(FloatBuffer b) { return b.duplicate(); }
    @Override public DoubleBuffer duplicate(DoubleBuffer b) { return b.duplicate(); }

    @Override public ByteBuffer slice(ByteBuffer b) { return wrap(getAddress(b), b.remaining()); }
    @Override public ByteBuffer slice(ByteBuffer b, int offset, int capacity) { return wrap(addr(b, b.position() + offset), capacity); }
    @Override public CharBuffer slice(CharBuffer b) { return wrapBufferChar(getAddress(b), b.remaining()); }
    @Override public CharBuffer slice(CharBuffer b, int offset, int capacity) { return wrapBufferChar(addr(b, b.position() + offset), capacity); }
    @Override public ShortBuffer slice(ShortBuffer b) { return wrapBufferShort(getAddress(b), b.remaining()); }
    @Override public ShortBuffer slice(ShortBuffer b, int offset, int capacity) { return wrapBufferShort(addr(b, b.position() + offset), capacity); }
    @Override public IntBuffer slice(IntBuffer b) { return wrapBufferInt(getAddress(b), b.remaining()); }
    @Override public IntBuffer slice(IntBuffer b, int offset, int capacity) { return wrapBufferInt(addr(b, b.position() + offset), capacity); }
    @Override public LongBuffer slice(LongBuffer b) { return wrapBufferLong(getAddress(b), b.remaining()); }
    @Override public LongBuffer slice(LongBuffer b, int offset, int capacity) { return wrapBufferLong(addr(b, b.position() + offset), capacity); }
    @Override public FloatBuffer slice(FloatBuffer b) { return wrapBufferFloat(getAddress(b), b.remaining()); }
    @Override public FloatBuffer slice(FloatBuffer b, int offset, int capacity) { return wrapBufferFloat(addr(b, b.position() + offset), capacity); }
    @Override public DoubleBuffer slice(DoubleBuffer b) { return wrapBufferDouble(getAddress(b), b.remaining()); }
    @Override public DoubleBuffer slice(DoubleBuffer b, int offset, int capacity) { return wrapBufferDouble(addr(b, b.position() + offset), capacity); }

    // ---- bulk memory ----

    @Override public void memset(long address, int value, long bytes) { fill(a(address), value & 0xFF, (int) bytes); }
    @Override public void memcpy(long src, long dst, long bytes) { move(a(src), a(dst), (int) bytes); }

    @Override public void memcpy(byte[] src, long dst, int off, int n) { fromArray(b(src), off, a(dst), n); }
    @Override public void memcpy(char[] src, long dst, int off, int n) { fromArray(c(src), off * 2, a(dst), n * 2); }
    @Override public void memcpy(short[] src, long dst, int off, int n) { fromArray(s(src), off * 2, a(dst), n * 2); }
    @Override public void memcpy(int[] src, long dst, int off, int n) { fromArray(i(src), off * 4, a(dst), n * 4); }
    @Override public void memcpy(long[] src, long dst, int off, int n) { fromArray(l(src), off * 8, a(dst), n * 8); }
    @Override public void memcpy(float[] src, long dst, int off, int n) { fromArray(f(src), off * 4, a(dst), n * 4); }
    @Override public void memcpy(double[] src, long dst, int off, int n) { fromArray(d(src), off * 8, a(dst), n * 8); }

    @Override public void memcpy(long src, byte[] dst, int off, int n) { toArray(a(src), b(dst), off, n); }
    @Override public void memcpy(long src, char[] dst, int off, int n) { toArray(a(src), c(dst), off * 2, n * 2); }
    @Override public void memcpy(long src, short[] dst, int off, int n) { toArray(a(src), s(dst), off * 2, n * 2); }
    @Override public void memcpy(long src, int[] dst, int off, int n) { toArray(a(src), i(dst), off * 4, n * 4); }
    @Override public void memcpy(long src, long[] dst, int off, int n) { toArray(a(src), l(dst), off * 8, n * 8); }
    @Override public void memcpy(long src, float[] dst, int off, int n) { toArray(a(src), f(dst), off * 4, n * 4); }
    @Override public void memcpy(long src, double[] dst, int off, int n) { toArray(a(src), d(dst), off * 8, n * 8); }

    // ---- strings ----

    @Override public String getStringASCII(long address) { int p = a(address); return decodeAscii(p, strlen(p)); }
    @Override public String getStringASCII(long address, int length) { return decodeAscii(a(address), length); }
    @Override public String getStringUTF8(long address) { int p = a(address); return decodeUtf8(p, strlen(p)); }
    @Override public String getStringUTF8(long address, int length) { return decodeUtf8(a(address), length); }
    @Override public String getStringUTF16(long address) { int p = a(address); return decodeUtf16(p, strlen16(p)); }
    @Override public String getStringUTF16(long address, int length) { return decodeUtf16(a(address), length >> 1); }

    @Override
    public void putStringASCII(String text, boolean nullTerminated, long target) {
        putStringASCII((CharSequence) text, nullTerminated, target);
    }

    @Override
    public void putStringASCII(CharSequence text, boolean nullTerminated, long target) {
        int p = a(target);
        int n = text.length();
        for (int k = 0; k < n; k++) {
            put8(p + k, text.charAt(k));
        }
        if (nullTerminated) {
            put8(p + n, 0);
        }
    }

    @Override
    public ByteBuffer allocateUTF8(String text, boolean nullTerminated, MemoryUtil.MemoryAllocator allocator) {
        int len = utf8Length(text) + (nullTerminated ? 1 : 0);
        long p = allocator.malloc(len);
        int written = encodeUtf8(text, a(p));
        if (nullTerminated) {
            put8(a(p) + written, 0);
        }
        return wrap(p, len);
    }

    @Override
    public void putStringUTF8(CharSequence text, boolean nullTerminated, long target) {
        int written = encodeUtf8(text.toString(), a(target));
        if (nullTerminated) {
            put8(a(target) + written, 0);
        }
    }

    @Override
    public int putStringUTF8(String text, boolean nullTerminated, long target, int maxBytes) {
        int written = encodeUtf8(text, a(target));
        if (nullTerminated) {
            put8(a(target) + written, 0);
            written++;
        }
        return written;
    }

    @Override
    public void putStringUTF16(String text, boolean nullTerminated, long target) {
        putStringUTF16((CharSequence) text, nullTerminated, target);
    }

    @Override
    public void putStringUTF16(CharSequence text, boolean nullTerminated, long target) {
        int p = a(target);
        int n = text.length();
        for (int k = 0; k < n; k++) {
            put16(p + 2 * k, text.charAt(k));
        }
        if (nullTerminated) {
            put16(p + 2 * n, 0);
        }
    }
}
