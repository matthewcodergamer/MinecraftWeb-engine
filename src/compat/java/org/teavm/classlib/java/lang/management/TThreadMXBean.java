package org.teavm.classlib.java.lang.management;

/** java.lang.management.ThreadMXBean (browser subset). */
public interface TThreadMXBean {
    boolean isObjectMonitorUsageSupported();

    boolean isSynchronizerUsageSupported();

    TThreadInfo[] dumpAllThreads(boolean lockedMonitors, boolean lockedSynchronizers);
}
