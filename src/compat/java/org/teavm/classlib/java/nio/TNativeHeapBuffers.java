package org.teavm.classlib.java.nio;

import org.teavm.interop.Import;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBufferView;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Direct NIO buffers over the emulated native heap (one JS ArrayBuffer, see web/native/heap.js).
 * A native address is a byte offset into that ArrayBuffer; address 0 is NULL.
 *
 * Lives in TeaVM's NIO package to reach the package-private JS buffer implementation. Callers outside
 * this package use the returned Object as java.nio.ByteBuffer (TeaVM maps java.nio.* to these classes).
 */
public final class TNativeHeapBuffers {
    private TNativeHeapBuffers() {
    }

    @JSBody(params = {"addr", "len"}, script = "return new Int8Array(__mcHeap.buffer, addr, len);")
    @Import(module = "mcHeap", name = "heapView")
    private static native Int8Array heapView(int addr, int len);

    @JSBody(params = "v", script = "return v.buffer === __mcHeap.buffer;")
    @Import(module = "mcHeap", name = "isHeapView")
    private static native boolean isHeapView(JSObject v);

    /** A little-endian direct ByteBuffer aliasing [addr, addr+capacity) of the native heap. */
    public static Object wrap(int addr, int capacity) {
        TByteBufferJsImpl buf = new TByteBufferJsImpl(null, 0, heapView(addr, capacity), true, false);
        buf.limit = capacity;
        buf.order(TByteOrder.LITTLE_ENDIAN);
        return buf;
    }

    /** Heap address of element 0 of the buffer, or -1 if the buffer does not live in the native heap. */
    public static int baseAddress(Object buffer) {
        if (!(buffer instanceof TArrayBufferViewProvider)) {
            return -1;
        }
        ArrayBufferView view = ((TArrayBufferViewProvider) buffer).getArrayBufferView();
        if (view == null || !isHeapView(view)) {
            return -1;
        }
        return view.getByteOffset();
    }

    /** Size in bytes of one element of the buffer. */
    public static int elementSize(Object buffer) {
        return ((TArrayBufferViewProvider) buffer).elementSize();
    }
}
