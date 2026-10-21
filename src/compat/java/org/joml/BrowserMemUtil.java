package org.joml;

/**
 * JOML picks sun.misc.Unsafe-based memory access when available. Browsers have no raw memory,
 * so MemUtil.createInstance() is patched (patches/rules.txt) to always use JOML's own
 * pure-NIO implementation, which JOML itself uses when Unsafe is unavailable.
 */
public final class BrowserMemUtil {
    private BrowserMemUtil() {
    }

    public static MemUtil createInstance() {
        return new MemUtil.MemUtilNIO();
    }
}
