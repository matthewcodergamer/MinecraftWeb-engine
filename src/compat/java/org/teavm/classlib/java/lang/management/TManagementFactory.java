package org.teavm.classlib.java.lang.management;

/**
 * java.lang.management.ManagementFactory for the browser runtime.
 *
 * <p>TeaVM's class library has no java.lang.management package. Minecraft 26.3
 * references it from its debug overlay, crash reports and system report, so every
 * path that touches those classes failed with
 * {@code NoClassDefFoundError: Class not found: java.lang.management.ManagementFactory}.
 * These are read-only browser equivalents: the JavaScript heap for memory, page
 * uptime for the runtime bean, and no GC/thread monitor beans at all.</p>
 */
public final class TManagementFactory {
    private static final long START_MILLIS = System.currentTimeMillis();
    private static final TRuntimeMXBean RUNTIME = new RuntimeBean();
    private static final TMemoryMXBean MEMORY = new MemoryBean();
    private static final TThreadMXBean THREADS = new ThreadBean();

    private TManagementFactory() {
    }

    public static TRuntimeMXBean getRuntimeMXBean() {
        return RUNTIME;
    }

    public static TMemoryMXBean getMemoryMXBean() {
        return MEMORY;
    }

    public static java.util.List<TGarbageCollectorMXBean> getGarbageCollectorMXBeans() {
        // The browser cannot observe or force a JavaScript engine's collections.
        return java.util.Collections.emptyList();
    }

    public static TThreadMXBean getThreadMXBean() {
        return THREADS;
    }

    private static final class RuntimeBean implements TRuntimeMXBean {
        @Override
        public long getUptime() {
            return Math.max(0L, System.currentTimeMillis() - START_MILLIS);
        }

        @Override
        public java.util.List<String> getInputArguments() {
            // There is no JVM command line in the browser.
            return java.util.Collections.emptyList();
        }
    }

    private static final class MemoryBean implements TMemoryMXBean {
        @Override
        public TMemoryUsage getHeapMemoryUsage() {
            Runtime runtime = Runtime.getRuntime();
            long total = runtime.totalMemory();
            long free = runtime.freeMemory();
            long used = total - free;
            return new TMemoryUsage(-1L, used < 0L ? 0L : used, total < 0L ? 0L : total, -1L);
        }

        @Override
        public TMemoryUsage getNonHeapMemoryUsage() {
            // A JavaScript engine has no separate non-heap pool to report.
            return new TMemoryUsage(-1L, 0L, 0L, -1L);
        }
    }

    private static final class ThreadBean implements TThreadMXBean {
        @Override
        public boolean isObjectMonitorUsageSupported() {
            return false;
        }

        @Override
        public boolean isSynchronizerUsageSupported() {
            return false;
        }

        @Override
        public TThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers) {
            return new TThreadInfo[0];
        }
    }
}
