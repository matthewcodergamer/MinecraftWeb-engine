package mcport.rt;

import org.teavm.jso.JSBody;

/** Bridge between ported Java code and the host page (status reporting, diagnostics). */
public final class BrowserRuntime {
    private BrowserRuntime() {
    }

    /** Publishes a key/value pair to window.__mc and the on-page log (read by automated tests). */
    @JSBody(params = {"k", "v"}, script = ""
            + "window.__mc = window.__mc || {}; window.__mc[k] = v;"
            + "if (window.__mcLog) window.__mcLog('REPORT', k + ' = ' + v);")
    public static native void report(String key, String value);

    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (depth > 1) {
                sb.append("\nCaused by: ");
            }
            sb.append(cur.getClass().getName()).append(": ").append(cur.getMessage());
            StackTraceElement[] st = cur.getStackTrace();
            if (st != null) {
                for (int i = 0; i < Math.min(st.length, 25); i++) {
                    sb.append("\n    at ").append(st[i]);
                }
            }
            org.teavm.jso.JSObject js = org.teavm.jso.JSExceptions.getJSException(cur);
            if (js != null) {
                sb.append("\n  JS stack: ").append(jsStack(js));
            }
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return sb.toString();
    }

    @JSBody(params = "e", script = "return String(e && e.stack ? e.stack : e);")
    private static native String jsStack(org.teavm.jso.JSObject e);
}
