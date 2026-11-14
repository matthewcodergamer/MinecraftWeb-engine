package org.teavm.classlib.java.lang.management;

/** java.lang.management.RuntimeMXBean (browser subset). */
public interface TRuntimeMXBean {
    long getUptime();

    java.util.List<String> getInputArguments();
}
