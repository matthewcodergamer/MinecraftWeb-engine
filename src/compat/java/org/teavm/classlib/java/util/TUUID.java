package org.teavm.classlib.java.util;

import java.util.Random;

/** Full java.util.UUID (TeaVM's version lacks the (long,long) constructor and accessors). */
public final class TUUID implements Comparable<TUUID>, java.io.Serializable {
    private final long mostSigBits;
    private final long leastSigBits;

    public TUUID(long mostSigBits, long leastSigBits) {
        this.mostSigBits = mostSigBits;
        this.leastSigBits = leastSigBits;
    }

    private static Random random;

    public static TUUID randomUUID() {
        if (random == null) {
            random = new Random();
        }
        byte[] b = new byte[16];
        random.nextBytes(b);
        b[6] = (byte) ((b[6] & 0x0f) | 0x40);
        b[8] = (byte) ((b[8] & 0x3f) | 0x80);
        return fromBytes(b);
    }

    public static TUUID nameUUIDFromBytes(byte[] name) {
        byte[] md5 = Md5.digest(name);
        md5[6] = (byte) ((md5[6] & 0x0f) | 0x30);
        md5[8] = (byte) ((md5[8] & 0x3f) | 0x80);
        return fromBytes(md5);
    }

    private static TUUID fromBytes(byte[] b) {
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (b[i] & 0xff);
        }
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (b[i] & 0xff);
        }
        return new TUUID(msb, lsb);
    }

    public static TUUID fromString(String name) {
        String[] parts = name.split("-");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Invalid UUID string: " + name);
        }
        long msb = Long.parseLong(parts[0], 16);
        msb = (msb << 16) | Long.parseLong(parts[1], 16);
        msb = (msb << 16) | Long.parseLong(parts[2], 16);
        long lsb = Long.parseLong(parts[3], 16);
        lsb = (lsb << 48) | Long.parseUnsignedLong(parts[4], 16);
        return new TUUID(msb, lsb);
    }

    public long getMostSignificantBits() {
        return mostSigBits;
    }

    public long getLeastSignificantBits() {
        return leastSigBits;
    }

    public int version() {
        return (int) ((mostSigBits >> 12) & 0x0f);
    }

    public int variant() {
        return (int) ((leastSigBits >>> (64 - (leastSigBits >>> 62))) & (leastSigBits >> 63));
    }

    @Override
    public String toString() {
        return digits(mostSigBits >> 32, 8) + "-" + digits(mostSigBits >> 16, 4) + "-" + digits(mostSigBits, 4)
                + "-" + digits(leastSigBits >> 48, 4) + "-" + digits(leastSigBits, 12);
    }

    private static String digits(long val, int digits) {
        long hi = 1L << (digits * 4);
        return Long.toHexString(hi | (val & (hi - 1))).substring(1);
    }

    @Override
    public int hashCode() {
        long hilo = mostSigBits ^ leastSigBits;
        return ((int) (hilo >> 32)) ^ (int) hilo;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TUUID)) {
            return false;
        }
        TUUID id = (TUUID) obj;
        return mostSigBits == id.mostSigBits && leastSigBits == id.leastSigBits;
    }

    @Override
    public int compareTo(TUUID val) {
        int c = Long.compare(mostSigBits, val.mostSigBits);
        return c != 0 ? c : Long.compare(leastSigBits, val.leastSigBits);
    }

    /** Minimal MD5 (RFC 1321) for name-based UUIDs, e.g. offline player UUIDs. */
    static final class Md5 {
        private static final int[] S = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5,
            9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10,
            15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};
        private static final int[] K = new int[64];

        static {
            for (int i = 0; i < 64; i++) {
                K[i] = (int) (long) (Math.abs(Math.sin(i + 1)) * 4294967296.0);
            }
        }

        static byte[] digest(byte[] msg) {
            int len = msg.length;
            int padded = ((len + 8) / 64 + 1) * 64;
            byte[] buf = new byte[padded];
            System.arraycopy(msg, 0, buf, 0, len);
            buf[len] = (byte) 0x80;
            long bits = (long) len * 8;
            for (int i = 0; i < 8; i++) {
                buf[padded - 8 + i] = (byte) (bits >>> (8 * i));
            }
            int a0 = 0x67452301;
            int b0 = 0xefcdab89;
            int c0 = 0x98badcfe;
            int d0 = 0x10325476;
            int[] m = new int[16];
            for (int off = 0; off < padded; off += 64) {
                for (int i = 0; i < 16; i++) {
                    int j = off + i * 4;
                    m[i] = (buf[j] & 0xff) | (buf[j + 1] & 0xff) << 8 | (buf[j + 2] & 0xff) << 16
                            | (buf[j + 3] & 0xff) << 24;
                }
                int a = a0;
                int b = b0;
                int c = c0;
                int d = d0;
                for (int i = 0; i < 64; i++) {
                    int f;
                    int g;
                    if (i < 16) {
                        f = (b & c) | (~b & d);
                        g = i;
                    } else if (i < 32) {
                        f = (d & b) | (~d & c);
                        g = (5 * i + 1) % 16;
                    } else if (i < 48) {
                        f = b ^ c ^ d;
                        g = (3 * i + 5) % 16;
                    } else {
                        f = c ^ (b | ~d);
                        g = (7 * i) % 16;
                    }
                    f = f + a + K[i] + m[g];
                    a = d;
                    d = c;
                    c = b;
                    b = b + Integer.rotateLeft(f, S[i]);
                }
                a0 += a;
                b0 += b;
                c0 += c;
                d0 += d;
            }
            byte[] out = new byte[16];
            int[] words = {a0, b0, c0, d0};
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    out[i * 4 + j] = (byte) (words[i] >>> (8 * j));
                }
            }
            return out;
        }
    }
}
