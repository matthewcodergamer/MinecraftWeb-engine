package org.teavm.classlib.java.security;

import java.nio.ByteBuffer;

/** java.security.MessageDigest with MD5, SHA-1 and SHA-256 (pure Java, per RFC 1321 / FIPS 180-4). */
public abstract class TMessageDigest extends TMessageDigestSpi {
    private final String algorithm;

    protected TMessageDigest(String algorithm) {
        this.algorithm = algorithm;
    }

    public static TMessageDigest getInstance(String algorithm) throws TNoSuchAlgorithmException {
        switch (algorithm.toUpperCase()) {
            case "MD5":
                return new Md5();
            case "SHA-1":
            case "SHA1":
            case "SHA":
                return new Sha1();
            case "SHA-256":
            case "SHA256":
                return new Sha256();
            default:
                throw new TNoSuchAlgorithmException(algorithm + " MessageDigest not available");
        }
    }

    public final String getAlgorithm() {
        return algorithm;
    }

    public final int getDigestLength() {
        return engineGetDigestLength();
    }

    public void update(byte input) {
        engineUpdate(input);
    }

    public void update(byte[] input, int offset, int len) {
        engineUpdate(input, offset, len);
    }

    public void update(byte[] input) {
        engineUpdate(input, 0, input.length);
    }

    public final void update(ByteBuffer input) {
        while (input.hasRemaining()) {
            engineUpdate(input.get());
        }
    }

    public byte[] digest() {
        byte[] r = engineDigest();
        engineReset();
        return r;
    }

    public int digest(byte[] buf, int offset, int len) {
        byte[] r = digest();
        System.arraycopy(r, 0, buf, offset, r.length);
        return r.length;
    }

    public byte[] digest(byte[] input) {
        update(input);
        return digest();
    }

    public void reset() {
        engineReset();
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }

    public static boolean isEqual(byte[] a, byte[] b) {
        return java.util.Arrays.equals(a, b);
    }

    /** Merkle-Damgard block hashing shared by MD5 / SHA-1 / SHA-256 (64-byte blocks). */
    abstract static class Block extends TMessageDigest {
        byte[] block = new byte[64];
        int blockLen;
        long total;
        private final boolean bigEndianLength;

        Block(String name, boolean bigEndianLength) {
            super(name);
            this.bigEndianLength = bigEndianLength;
        }

        abstract void compress(byte[] b);

        abstract byte[] output();

        abstract void init();

        @Override
        protected void engineUpdate(byte input) {
            block[blockLen++] = input;
            total++;
            if (blockLen == 64) {
                compress(block);
                blockLen = 0;
            }
        }

        @Override
        protected void engineUpdate(byte[] input, int offset, int len) {
            for (int i = 0; i < len; i++) {
                engineUpdate(input[offset + i]);
            }
        }

        @Override
        protected byte[] engineDigest() {
            long bits = total * 8;
            engineUpdate((byte) 0x80);
            while (blockLen != 56) {
                engineUpdate((byte) 0);
            }
            for (int i = 0; i < 8; i++) {
                int shift = bigEndianLength ? (56 - 8 * i) : (8 * i);
                engineUpdate((byte) (bits >>> shift));
            }
            return output();
        }

        @Override
        protected void engineReset() {
            blockLen = 0;
            total = 0;
            init();
        }

        @Override
        public Object clone() throws CloneNotSupportedException {
            Block c = (Block) super.clone();
            c.block = block.clone();
            c.copyState(this);
            return c;
        }

        abstract void copyState(Block from);
    }

    static final class Md5 extends Block {
        private static final int[] S = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14,
                20, 5, 9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21,
                6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};
        private static final int[] K = new int[64];

        static {
            for (int i = 0; i < 64; i++) {
                K[i] = (int) (long) Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296.0);
            }
        }

        int[] h = new int[4];

        Md5() {
            super("MD5", false);
            init();
        }

        @Override
        void init() {
            h[0] = 0x67452301;
            h[1] = 0xefcdab89;
            h[2] = 0x98badcfe;
            h[3] = 0x10325476;
        }

        @Override
        protected int engineGetDigestLength() {
            return 16;
        }

        @Override
        void compress(byte[] b) {
            int[] m = new int[16];
            for (int i = 0; i < 16; i++) {
                m[i] = (b[4 * i] & 0xff) | (b[4 * i + 1] & 0xff) << 8 | (b[4 * i + 2] & 0xff) << 16 | (b[4 * i + 3] & 0xff) << 24;
            }
            int a = h[0];
            int bb = h[1];
            int c = h[2];
            int d = h[3];
            for (int i = 0; i < 64; i++) {
                int f;
                int g;
                if (i < 16) {
                    f = (bb & c) | (~bb & d);
                    g = i;
                } else if (i < 32) {
                    f = (d & bb) | (~d & c);
                    g = (5 * i + 1) & 15;
                } else if (i < 48) {
                    f = bb ^ c ^ d;
                    g = (3 * i + 5) & 15;
                } else {
                    f = c ^ (bb | ~d);
                    g = (7 * i) & 15;
                }
                int tmp = d;
                d = c;
                c = bb;
                bb = bb + Integer.rotateLeft(a + f + K[i] + m[g], S[i]);
                a = tmp;
            }
            h[0] += a;
            h[1] += bb;
            h[2] += c;
            h[3] += d;
        }

        @Override
        byte[] output() {
            byte[] r = new byte[16];
            for (int i = 0; i < 16; i++) {
                r[i] = (byte) (h[i >> 2] >>> (8 * (i & 3)));
            }
            return r;
        }

        @Override
        void copyState(Block from) {
            h = ((Md5) from).h.clone();
        }
    }

    static final class Sha1 extends Block {
        int[] h = new int[5];

        Sha1() {
            super("SHA-1", true);
            init();
        }

        @Override
        void init() {
            h[0] = 0x67452301;
            h[1] = 0xEFCDAB89;
            h[2] = 0x98BADCFE;
            h[3] = 0x10325476;
            h[4] = 0xC3D2E1F0;
        }

        @Override
        protected int engineGetDigestLength() {
            return 20;
        }

        @Override
        void compress(byte[] b) {
            int[] w = new int[80];
            for (int i = 0; i < 16; i++) {
                w[i] = (b[4 * i] & 0xff) << 24 | (b[4 * i + 1] & 0xff) << 16 | (b[4 * i + 2] & 0xff) << 8 | (b[4 * i + 3] & 0xff);
            }
            for (int i = 16; i < 80; i++) {
                w[i] = Integer.rotateLeft(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);
            }
            int a = h[0];
            int bb = h[1];
            int c = h[2];
            int d = h[3];
            int e = h[4];
            for (int i = 0; i < 80; i++) {
                int f;
                int k;
                if (i < 20) {
                    f = (bb & c) | (~bb & d);
                    k = 0x5A827999;
                } else if (i < 40) {
                    f = bb ^ c ^ d;
                    k = 0x6ED9EBA1;
                } else if (i < 60) {
                    f = (bb & c) | (bb & d) | (c & d);
                    k = 0x8F1BBCDC;
                } else {
                    f = bb ^ c ^ d;
                    k = 0xCA62C1D6;
                }
                int t = Integer.rotateLeft(a, 5) + f + e + k + w[i];
                e = d;
                d = c;
                c = Integer.rotateLeft(bb, 30);
                bb = a;
                a = t;
            }
            h[0] += a;
            h[1] += bb;
            h[2] += c;
            h[3] += d;
            h[4] += e;
        }

        @Override
        byte[] output() {
            byte[] r = new byte[20];
            for (int i = 0; i < 20; i++) {
                r[i] = (byte) (h[i >> 2] >>> (24 - 8 * (i & 3)));
            }
            return r;
        }

        @Override
        void copyState(Block from) {
            h = ((Sha1) from).h.clone();
        }
    }

    static final class Sha256 extends Block {
        private static final int[] K = {0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
                0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
                0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152,
                0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138,
                0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70,
                0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
                0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa,
                0xa4506ceb, 0xbef9a3f7, 0xc67178f2};
        int[] h = new int[8];

        Sha256() {
            super("SHA-256", true);
            init();
        }

        @Override
        void init() {
            int[] iv = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
            System.arraycopy(iv, 0, h, 0, 8);
        }

        @Override
        protected int engineGetDigestLength() {
            return 32;
        }

        @Override
        void compress(byte[] b) {
            int[] w = new int[64];
            for (int i = 0; i < 16; i++) {
                w[i] = (b[4 * i] & 0xff) << 24 | (b[4 * i + 1] & 0xff) << 16 | (b[4 * i + 2] & 0xff) << 8 | (b[4 * i + 3] & 0xff);
            }
            for (int i = 16; i < 64; i++) {
                int s0 = Integer.rotateRight(w[i - 15], 7) ^ Integer.rotateRight(w[i - 15], 18) ^ (w[i - 15] >>> 3);
                int s1 = Integer.rotateRight(w[i - 2], 17) ^ Integer.rotateRight(w[i - 2], 19) ^ (w[i - 2] >>> 10);
                w[i] = w[i - 16] + s0 + w[i - 7] + s1;
            }
            int a = h[0];
            int bb = h[1];
            int c = h[2];
            int d = h[3];
            int e = h[4];
            int f = h[5];
            int g = h[6];
            int hh = h[7];
            for (int i = 0; i < 64; i++) {
                int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
                int ch = (e & f) ^ (~e & g);
                int t1 = hh + s1 + ch + K[i] + w[i];
                int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
                int maj = (a & bb) ^ (a & c) ^ (bb & c);
                int t2 = s0 + maj;
                hh = g;
                g = f;
                f = e;
                e = d + t1;
                d = c;
                c = bb;
                bb = a;
                a = t1 + t2;
            }
            h[0] += a;
            h[1] += bb;
            h[2] += c;
            h[3] += d;
            h[4] += e;
            h[5] += f;
            h[6] += g;
            h[7] += hh;
        }

        @Override
        byte[] output() {
            byte[] r = new byte[32];
            for (int i = 0; i < 32; i++) {
                r[i] = (byte) (h[i >> 2] >>> (24 - 8 * (i & 3)));
            }
            return r;
        }

        @Override
        void copyState(Block from) {
            h = ((Sha256) from).h.clone();
        }
    }
}
