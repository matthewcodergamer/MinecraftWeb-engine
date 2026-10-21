package mcport.rt;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;
import org.teavm.jso.JSBody;

/** SLF4J logger that writes to the browser console and the on-page log. */
public final class BrowserLogger extends LegacyAbstractLogger {
    private static final Map<String, Logger> LOGGERS = new HashMap<>();
    private static int rootLevel = Level.INFO.toInt();

    private BrowserLogger(String name) {
        this.name = name;
    }

    public static synchronized Logger get(String name) {
        return LOGGERS.computeIfAbsent(name, BrowserLogger::new);
    }

    public static void setRootLevel(Level level) {
        rootLevel = level.toInt();
    }

    private static boolean enabled(Level level) {
        return level.toInt() >= rootLevel;
    }

    @Override
    public boolean isTraceEnabled() {
        return enabled(Level.TRACE);
    }

    @Override
    public boolean isDebugEnabled() {
        return enabled(Level.DEBUG);
    }

    @Override
    public boolean isInfoEnabled() {
        return enabled(Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return enabled(Level.WARN);
    }

    @Override
    public boolean isErrorEnabled() {
        return enabled(Level.ERROR);
    }

    @Override
    protected String getFullyQualifiedCallerName() {
        return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(Level level, Marker marker, String pattern, Object[] args,
            Throwable throwable) {
        String msg = MessageFormatter.basicArrayFormat(pattern, args);
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(Thread.currentThread().getName()).append('/').append(level).append("] ");
        if (marker != null) {
            sb.append('[').append(marker.getName()).append("] ");
        }
        sb.append(msg);
        if (throwable != null) {
            sb.append('\n').append(BrowserRuntime.describe(throwable));
        }
        emit(level.toString(), sb.toString());
    }

    @JSBody(params = {"level", "text"}, script = ""
            + "var f = level === 'ERROR' ? console.error : level === 'WARN' ? console.warn : console.log;"
            + "f.call(console, text);"
            + "if (window.__mcLog) window.__mcLog(level, text);")
    private static native void emit(String level, String text);
}
