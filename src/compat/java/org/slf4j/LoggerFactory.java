package org.slf4j;

import mcport.rt.BrowserLogger;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;
import org.slf4j.IMarkerFactory;

/**
 * Browser replacement for org.slf4j.LoggerFactory. The original discovers providers via
 * ServiceLoader/classpath scanning; in the browser the only provider is BrowserLogger.
 */
public final class LoggerFactory {
    private static final ILoggerFactory FACTORY = BrowserLogger::get;
    private static final IMarkerFactory MARKER_FACTORY = new BasicMarkerFactory();
    private static final MDCAdapter MDC_ADAPTER = new NOPMDCAdapter();
    private static final SLF4JServiceProvider PROVIDER = new SLF4JServiceProvider() {
        @Override
        public ILoggerFactory getLoggerFactory() {
            return FACTORY;
        }

        @Override
        public IMarkerFactory getMarkerFactory() {
            return MARKER_FACTORY;
        }

        @Override
        public MDCAdapter getMDCAdapter() {
            return MDC_ADAPTER;
        }

        @Override
        public String getRequestedApiVersion() {
            return "2.0.99";
        }

        @Override
        public void initialize() {
            // BrowserLogger and its marker/MDC adapters are ready eagerly.
        }
    };

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

    /**
     * SLF4J 2.x MarkerFactory calls this method during static initialization.
     * Keep it local: ServiceLoader discovery is not available in the browser.
     */
    public static SLF4JServiceProvider getProvider() {
        return PROVIDER;
    }
}