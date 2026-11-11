package mcport.rt.patch;

/** Safe byte-array replacements for Netty's Unsafe-backed PlatformDependent0 primitives. */
public final class NettyUnsafePatches {
    private static final int HASH_CODE_ASCII_SEED = 0xc2b2ae35;
    private static final int HASH_CODE_C1 = 0xcc9e2d51;
    private static final int HASH_CODE_C2 = 0x1b873593;

    private NettyUnsafePatches() {}

    public static boolean equals(byte[] a, int aPos, byte[] b, int bPos, int length) {
        for (int i = 0; i < length; i++) {
            if (a[aPos + i] != b[bPos + i]) return false;
        }
        return true;
    }

    public static int hashCodeAscii(byte[] bytes, int start, int length) {
        int hash = HASH_CODE_ASCII_SEED;
        int remaining = length & 7;
        int base = start;
        int end = base + remaining;
        for (int i = base - 8 + length; i >= end; i -= 8) {
            hash = hashCodeAsciiCompute(getLongLE(bytes, i), hash);
        }
        if (remaining == 0) return hash;
        int hcConst = HASH_CODE_C1;
        if (remaining != 2 && remaining != 4 && remaining != 6) {
            hash = hash * HASH_CODE_C1 + (bytes[base] & 0x1f);
            hcConst = HASH_CODE_C2;
            base++;
        }
        if (remaining != 1 && remaining != 4 && remaining != 5) {
            hash = hash * hcConst + (getShortLE(bytes, base) & 0x1f1f);
            hcConst = hcConst == HASH_CODE_C1 ? HASH_CODE_C2 : HASH_CODE_C1;
            base += 2;
        }
        if (remaining >= 4) {
            hash = hash * hcConst + (getIntLE(bytes, base) & 0x1f1f1f1f);
        }
        return hash;
    }

    private static int hashCodeAsciiCompute(long value, int hash) {
        return hash * HASH_CODE_C1
                + ((int) value & 0x1f1f1f1f) * HASH_CODE_C2
                + (int) ((value & 0x1f1f1f1f00000000L) >>> 32);
    }

    private static short getShortLE(byte[] b, int i) {
        return (short) ((b[i] & 255) | ((b[i + 1] & 255) << 8));
    }

    private static int getIntLE(byte[] b, int i) {
        return (b[i] & 255) | ((b[i + 1] & 255) << 8)
                | ((b[i + 2] & 255) << 16) | (b[i + 3] << 24);
    }

    private static long getLongLE(byte[] b, int i) {
        return ((long) (b[i] & 255))
                | ((long) (b[i + 1] & 255) << 8)
                | ((long) (b[i + 2] & 255) << 16)
                | ((long) (b[i + 3] & 255) << 24)
                | ((long) (b[i + 4] & 255) << 32)
                | ((long) (b[i + 5] & 255) << 40)
                | ((long) (b[i + 6] & 255) << 48)
                | ((long) (b[i + 7] & 255) << 56);
    }

    public static short getShort(byte[] data, int index) {
        return getShortLE(data, index);
    }

    public static void putShort(byte[] data, int index, short value) {
        data[index] = (byte) value;
        data[index + 1] = (byte) (value >>> 8);
    }

    /** Raw addresses do not exist in the browser's managed-memory model. */
    public static short getShort(long address) {
        throw new UnsupportedOperationException("Netty Unsafe address access is unavailable in the browser");
    }

    /** Raw addresses do not exist in the browser's managed-memory model. */
    public static void putShort(long address, short value) {
        throw new UnsupportedOperationException("Netty Unsafe address access is unavailable in the browser");
    }
}
