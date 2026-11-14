package mcport.rt;

import org.teavm.jso.JSBody;

/** Bridge between ported Java code and the host page (status reporting, diagnostics). */
public final class BrowserRuntime {
    private BrowserRuntime() {
    }

    /** Publishes a key/value pair to window.__mc and the on-page log (read by automated tests). */
    @JSBody(params = {"k", "v"}, script = ""
            + "window.__mc = window.__mc || {}; window.__mc[k] = v;"
            + "if (k === 'trace') {"
            + "  console.log('[JAVA_TRACE] ' + v + ' @' + Math.round(performance.now()) + 'ms');"
            + "}"
            + "if (k === 'stage') {"
            + "  console.log('[JAVA_STAGE] ' + v + ' @' + Math.round(performance.now()) + 'ms');"
            + "  if (window.parent !== window) window.parent.postMessage({source:'mc-brain',type:'java-stage',stage:v}, '*');"
            + "}"
            + "if (k === 'monitor') {"
            + "  console.log('[JAVA_MONITOR] ' + v + ' @' + Math.round(performance.now()) + 'ms');"
            + "}"
            + "if (window.__mcLog) window.__mcLog('REPORT', k + ' = ' + v);")
    public static native void report(String key, String value);

    /** Writes straight to the host page's console, independently of the REPORT plumbing. */
    @JSBody(params = "text", script = "console.log(text);")
    public static native void log(String text);

    /** Captures tab visibility and focus at the exact Java fiber suspension point. */
    @JSBody(script = "return 'visibilityState=' + document.visibilityState + ' hasFocus=' + document.hasFocus();")
    public static native String suspendDocumentState();

    /**
     * Diagnostic text for a throwable: the Java type/message, TeaVM's synthetic frames, and the real
     * JavaScript stack (Wasm function names). NPEs thrown by the JavaScript engine arrive as host
     * objects without the full Throwable surface, so every step is guarded: a crash reporter must
     * never fail while reporting a crash.
     */
    public static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        try {
            Throwable cur = t;
            int depth = 0;
            while (cur != null && depth++ < 8) {
                if (depth > 1) {
                    sb.append("\nCaused by: ");
                }
                appendSafely(sb, cur);
                Throwable cause;
                try {
                    cause = cur.getCause();
                } catch (Throwable ignored) {
                    cause = null;
                }
                cur = cause == cur ? null : cause;
            }
        } catch (Throwable failure) {
            sb.append("\n[describe failed: ").append(failure).append(']');
        }
        return sb.toString();
    }

    private static void appendSafely(StringBuilder sb, Throwable cur) {
        try {
            sb.append(cur.getClass().getName()).append(": ").append(cur.getMessage());
        } catch (Throwable ignored) {
            sb.append("host exception: ").append(cur);
        }
        try {
            StackTraceElement[] st = cur.getStackTrace();
            if (st != null) {
                for (int i = 0; i < Math.min(st.length, 25); i++) {
                    sb.append("\n    at ").append(st[i]);
                }
            }
        } catch (Throwable ignored) {
            // Host exceptions do not expose Java stack frames; the JS stack below is authoritative.
        }
        try {
            org.teavm.jso.JSObject js = org.teavm.jso.JSExceptions.getJSException(cur);
            if (js != null) {
                sb.append("\n  JS stack: ").append(jsStack(js));
            }
        } catch (Throwable ignored) {
            // Not a JavaScript exception.
        }
    }

    @JSBody(params = "e", script = "return String(e && e.stack ? e.stack : e);")
    private static native String jsStack(org.teavm.jso.JSObject e);
}
