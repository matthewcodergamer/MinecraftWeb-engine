package org.teavm.classlib.java.util;

/** java.util.HexFormat (Java 17) - the subset of the public API with JDK semantics. */
public final class THexFormat {
    private static final THexFormat HEX = new THexFormat("", "", "", false);

    private final String delimiter;
    private final String prefix;
    private final String suffix;
    private final boolean upper;

    private THexFormat(String delimiter, String prefix, String suffix, boolean upper) {
        this.delimiter = delimiter;
        this.prefix = prefix;
        this.suffix = suffix;
        this.upper = upper;
    }

    public static THexFormat of() {
        return HEX;
    }

    public static THexFormat ofDelimiter(String delimiter) {
        return new THexFormat(delimiter, "", "", false);
    }

    public THexFormat withDelimiter(String d) {
        return new THexFormat(d, prefix, suffix, upper);
    }

    public THexFormat withPrefix(String p) {
        return new THexFormat(delimiter, p, suffix, upper);
    }

    public THexFormat withSuffix(String s) {
        return new THexFormat(delimiter, prefix, s, upper);
    }

    public THexFormat withUpperCase() {
        return new THexFormat(delimiter, prefix, suffix, true);
    }

    public THexFormat withLowerCase() {
        return new THexFormat(delimiter, prefix, suffix, false);
    }

    public String delimiter() {
        return delimiter;
    }

    public String prefix() {
        return prefix;
    }

    public String suffix() {
        return suffix;
    }

    public boolean isUpperCase() {
        return upper;
    }

    private char digit(int v) {
        v &= 0xf;
        return (char) (v < 10 ? '0' + v : (upper ? 'A' : 'a') + v - 10);
    }

    public String formatHex(byte[] bytes) {
        return formatHex(bytes, 0, bytes.length);
    }

    public String formatHex(byte[] bytes, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append(delimiter);
            }
            sb.append(prefix).append(digit(bytes[i] >> 4)).append(digit(bytes[i])).append(suffix);
        }
        return sb.toString();
    }

    public byte[] parseHex(CharSequence s) {
        String str = s.toString();
        int stride = prefix.length() + 2 + suffix.length() + delimiter.length();
        if (str.isEmpty()) {
            return new byte[0];
        }
        int n = (str.length() + delimiter.length()) / stride;
        byte[] out = new byte[n];
        int pos = 0;
        for (int i = 0; i < n; i++) {
            pos += prefix.length();
            out[i] = (byte) fromHexDigits(str, pos, pos + 2);
            pos += 2 + suffix.length() + delimiter.length();
        }
        return out;
    }

    public char toLowHexDigit(int value) {
        return digit(value);
    }

    public char toHighHexDigit(int value) {
        return digit(value >> 4);
    }

    private String digits(long value, int n) {
        char[] c = new char[n];
        for (int i = n - 1; i >= 0; i--) {
            c[i] = digit((int) value);
            value >>>= 4;
        }
        return new String(c);
    }

    public String toHexDigits(byte value) {
        return digits(value, 2);
    }

    public String toHexDigits(char value) {
        return digits(value, 4);
    }

    public String toHexDigits(short value) {
        return digits(value, 4);
    }

    public String toHexDigits(int value) {
        return digits(value, 8);
    }

    public String toHexDigits(long value) {
        return digits(value, 16);
    }

    public String toHexDigits(long value, int digits) {
        if (digits < 0 || digits > 16) {
            throw new IllegalArgumentException("number of digits: " + digits);
        }
        return digits(value, digits);
    }

    public static boolean isHexDigit(int ch) {
        return Character.digit(ch, 16) >= 0;
    }

    public static int fromHexDigit(int ch) {
        int v = Character.digit(ch, 16);
        if (v < 0) {
            throw new NumberFormatException("not a hexadecimal digit: \"" + (char) ch + "\" = " + ch);
        }
        return v;
    }

    public static int fromHexDigits(CharSequence s) {
        return fromHexDigits(s, 0, s.length());
    }

    public static int fromHexDigits(CharSequence s, int from, int to) {
        if (to - from > 8) {
            throw new IllegalArgumentException("string length greater than 8: " + (to - from));
        }
        int v = 0;
        for (int i = from; i < to; i++) {
            v = (v << 4) | fromHexDigit(s.charAt(i));
        }
        return v;
    }

    public static long fromHexDigitsToLong(CharSequence s) {
        return fromHexDigitsToLong(s, 0, s.length());
    }

    public static long fromHexDigitsToLong(CharSequence s, int from, int to) {
        if (to - from > 16) {
            throw new IllegalArgumentException("string length greater than 16: " + (to - from));
        }
        long v = 0;
        for (int i = from; i < to; i++) {
            v = (v << 4) | fromHexDigit(s.charAt(i));
        }
        return v;
    }

    @Override
    public String toString() {
        return "uppercase: " + upper + ", delimiter: \"" + delimiter + "\", prefix: \"" + prefix + "\", suffix: \""
                + suffix + "\"";
    }
}
