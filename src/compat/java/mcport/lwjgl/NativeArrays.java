package mcport.lwjgl;

/** Marshals Java arrays passed to LWJGL native functions through the native heap (in before, out after). */
public final class NativeArrays {
    private static final BrowserMemoryBackend M = BrowserMemoryBackend.INSTANCE;

    private NativeArrays() {
    }

    public static long in(short[] a) {
        if (a == null) {
            return 0;
        }
        long p = BrowserAllocator.malloc0(a.length * 2L);
        M.memcpy(a, p, 0, a.length);
        return p;
    }

    public static long in(int[] a) {
        if (a == null) {
            return 0;
        }
        long p = BrowserAllocator.malloc0(a.length * 4L);
        M.memcpy(a, p, 0, a.length);
        return p;
    }

    public static long in(long[] a) {
        if (a == null) {
            return 0;
        }
        long p = BrowserAllocator.malloc0(a.length * 8L);
        M.memcpy(a, p, 0, a.length);
        return p;
    }

    public static long in(float[] a) {
        if (a == null) {
            return 0;
        }
        long p = BrowserAllocator.malloc0(a.length * 4L);
        M.memcpy(a, p, 0, a.length);
        return p;
    }

    public static long in(double[] a) {
        if (a == null) {
            return 0;
        }
        long p = BrowserAllocator.malloc0(a.length * 8L);
        M.memcpy(a, p, 0, a.length);
        return p;
    }

    public static void out(short[] a, long p) {
        if (a != null) {
            M.memcpy(p, a, 0, a.length);
            BrowserAllocator.free0(p);
        }
    }

    public static void out(int[] a, long p) {
        if (a != null) {
            M.memcpy(p, a, 0, a.length);
            BrowserAllocator.free0(p);
        }
    }

    public static void out(long[] a, long p) {
        if (a != null) {
            M.memcpy(p, a, 0, a.length);
            BrowserAllocator.free0(p);
        }
    }

    public static void out(float[] a, long p) {
        if (a != null) {
            M.memcpy(p, a, 0, a.length);
            BrowserAllocator.free0(p);
        }
    }

    public static void out(double[] a, long p) {
        if (a != null) {
            M.memcpy(p, a, 0, a.length);
            BrowserAllocator.free0(p);
        }
    }
}
