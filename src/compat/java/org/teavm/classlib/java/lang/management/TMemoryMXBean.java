package org.teavm.classlib.java.lang.management;

/** java.lang.management.MemoryMXBean (browser subset). */
public interface TMemoryMXBean {
    TMemoryUsage getHeapMemoryUsage();

    TMemoryUsage getNonHeapMemoryUsage();
}
