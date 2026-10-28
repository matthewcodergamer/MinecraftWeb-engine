package mcport.lwjgl;

import org.teavm.jso.JSBody;

/** LWJGL's STB bindings declare their own JNI natives; they are routed to web/native/stb.js. */
public final class StbNatives {
    private StbNatives() {
    }

    @JSBody(params = {"name"}, script = "return __mcNative.fn[__mcNative.lookup('stb', name)];")
    private static native org.teavm.jso.JSObject fn(String name);

    @JSBody(params = {"b", "len", "x", "y", "c", "req"},
            script = "return __mcNative.impls.stbi_load_from_memory(b, len, x, y, c, req);")
    private static native double load(double b, int len, double x, double y, double c, int req);

    public static long loadFromMemory(long buffer, int len, long x, long y, long comp, int req) {
        return (long) load(buffer, len, x, y, comp, req);
    }

    @JSBody(script = "return __mcNative.impls.stbi_failure_reason();")
    private static native double failure();

    public static long failureReason() {
        return (long) failure();
    }

    @JSBody(params = "p", script = "__mcNative.impls.stbi_image_free(p);")
    private static native void free(double p);

    public static void imageFree(long p) {
        free(p);
    }

    @JSBody(params = {"i", "iw", "ih", "is", "o", "ow", "oh", "os", "l"},
            script = "return __mcNative.impls.stbir_resize_uint8_linear(i, iw, ih, is, o, ow, oh, os, l);")
    private static native double resize(double i, int iw, int ih, int is, double o, int ow, int oh, int os, int l);

    public static long resizeLinear(long in, int iw, int ih, int is, long out, int ow, int oh, int os, int layout) {
        return (long) resize(in, iw, ih, is, out, ow, oh, os, layout);
    }

    @JSBody(params = {"fn", "ctx", "w", "h", "c", "data", "stride"},
            script = "return __mcNative.impls.stbi_write_png_to_func(fn, ctx, w, h, c, data, stride);")
    private static native int writePng0(double fn, double ctx, int w, int h, int c, double data, int stride);

    public static int writePng(long fn, long ctx, int w, int h, int comp, long data, int stride) {
        return writePng0(fn, ctx, w, h, comp, data, stride);
    }
}
