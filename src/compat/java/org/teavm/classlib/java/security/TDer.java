package org.teavm.classlib.java.security;

import java.math.BigInteger;

/** Minimal DER encoder for the RSA key encodings Minecraft expects. */
final class TDer {
    private TDer() {
    }

    static byte[] sequence(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] content = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, content, offset, part.length);
            offset += part.length;
        }
        return wrap(0x30, content);
    }

    static byte[] integer(BigInteger value) {
        return wrap(0x02, value.toByteArray());
    }

    static byte[] bitString(byte[] value) {
        byte[] content = new byte[value.length + 1];
        // The first content byte is the number of unused bits in the final octet.
        System.arraycopy(value, 0, content, 1, value.length);
        return wrap(0x03, content);
    }

    static byte[] octetString(byte[] value) {
        return wrap(0x04, value);
    }

    static byte[] nullValue() {
        return new byte[] {0x05, 0x00};
    }

    /** rsaEncryption: 1.2.840.113549.1.1.1 */
    static byte[] rsaAlgorithmIdentifier() {
        return sequence(new byte[] {0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86,
                (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01}, nullValue());
    }

    private static byte[] wrap(int tag, byte[] content) {
        byte[] length = length(content.length);
        byte[] result = new byte[1 + length.length + content.length];
        result[0] = (byte) tag;
        System.arraycopy(length, 0, result, 1, length.length);
        System.arraycopy(content, 0, result, 1 + length.length, content.length);
        return result;
    }

    private static byte[] length(int length) {
        if (length < 0x80) {
            return new byte[] {(byte) length};
        }
        int bytes = 0;
        int value = length;
        while (value != 0) {
            bytes++;
            value >>>= 8;
        }
        byte[] result = new byte[bytes + 1];
        result[0] = (byte) (0x80 | bytes);
        for (int i = bytes; i > 0; i--) {
            result[i] = (byte) length;
            length >>>= 8;
        }
        return result;
    }
}
