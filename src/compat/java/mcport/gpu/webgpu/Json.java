package mcport.gpu.webgpu;

/** Minimal JSON writer for descriptors passed to web/gpu/webgpu.js. */
final class Json {
    private final StringBuilder sb = new StringBuilder();
    private boolean needComma;

    Json obj() { sep(); sb.append('{'); needComma = false; return this; }
    Json endObj() { sb.append('}'); needComma = true; return this; }
    Json arr() { sep(); sb.append('['); needComma = false; return this; }
    Json endArr() { sb.append(']'); needComma = true; return this; }

    Json key(String k) {
        sep();
        str(k);
        sb.append(':');
        needComma = false;
        return this;
    }

    Json val(String s) { sep(); if (s == null) sb.append("null"); else str(s); needComma = true; return this; }
    Json val(double d) { sep(); sb.append(Double.isFinite(d) ? (d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d)) : "0"); needComma = true; return this; }
    Json val(boolean b) { sep(); sb.append(b); needComma = true; return this; }
    Json nul() { sep(); sb.append("null"); needComma = true; return this; }

    private void sep() {
        if (needComma) {
            sb.append(',');
        }
    }

    private void str(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}
