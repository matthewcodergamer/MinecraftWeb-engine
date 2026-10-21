package org.slf4j;

import mcport.rt.BrowserLogger;

/**
 * Browser replacement for org.slf4j.LoggerFactory. The original discovers providers via
 * ServiceLoader/classpath scanning; in the browser the only provider is BrowserLogger.
 */
public final class LoggerFactory {
    private static final ILoggerFactory FACTORY = BrowserLogger::get;

    private LoggerFactory() {
    }

    public static Logger getLogger(String name) {
        return BrowserLogger.get(name);
    }

    public static Logger getLogger(Class<?> clazz) {
        return BrowserLogger.get(clazz.getName());
    }

    public static ILoggerFactory getILoggerFactory() {
        return FACTORY;
    }
}
