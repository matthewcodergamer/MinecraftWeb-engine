package java.nio;

/**
 * Compile-time signature of org.teavm.classlib.java.nio.TNativeHeapBuffers (TeaVM maps that class to this
 * name). Only used by javac; never passed to TeaVM.
 */
public final class NativeHeapBuffers {
    private NativeHeapBuffers() {
    }

    public static Object wrap(int addr, int capacity) {
        throw new UnsupportedOperationException();
    }

    public static int baseAddress(Object buffer) {
        throw new UnsupportedOperationException();
    }

    public static int elementSize(Object buffer) {
        throw new UnsupportedOperationException();
    }
}
