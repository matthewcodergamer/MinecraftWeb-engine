package com.mojang.logging;

import java.util.function.Supplier;
import mcport.rt.BrowserLogger;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.event.Level;

/**
 * Browser replacement for Mojang's com.mojang.logging.LogUtils (same public API).
 * The original binds SLF4J to log4j-core, which depends on plugin scanning,
 * reflection and file appenders; here loggers write to the browser console.
 */
public class LogUtils {
    public static final String FATAL_MARKER_ID = "FATAL";
    public static final Marker FATAL_MARKER = new BasicMarkerFactory().getMarker(FATAL_MARKER_ID);

    public static boolean isLoggerActive() {
        return true;
    }

    public static void configureRootLoggingLevel(Level level) {
        BrowserLogger.setRootLevel(level);
    }

    public static Object defer(Supplier<Object> result) {
        return new Object() {
            @Override
            public String toString() {
                return String.valueOf(result.get());
            }
        };
    }

    public static Logger getLogger() {
        return BrowserLogger.get("minecraft");
    }
}
