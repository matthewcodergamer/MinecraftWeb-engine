package org.teavm.classlib.java.lang.management;

/**
 * java.lang.management.MemoryUsage for the browser runtime.
 *
 * <p>TeaVM's class library has no java.lang.management package at all. The vanilla
 * client references it from debug screens and crash reports; without these classes
 * TeaVM's lenient linkage throws NoClassDefFoundError as soon as such a class is
 * initialized. The values are a snapshot, exactly like the JDK record.</p>
 */
public class TMemoryUsage {
    private final long init;
    private final long used;
    private final long committed;
    private final long max;

    public TMemoryUsage(long init, long used, long committed, long max) {
        if (init < -1 || used < -1 || committed < -1 || max < -1) {
            throw new IllegalArgumentException("invalid memory usage");
        }
        this.init = init;
        this.used = used;
        this.committed = committed;
        this.max = max;
    }

    public long getInit() {
        return init;
    }

    public long getUsed() {
        return used;
    }

    public long getCommitted() {
        return committed;
    }

    public long getMax() {
        return max;
    }

    @Override
    public String toString() {
        return "init = " + init + "(0) used = " + used + "(0) committed = " + committed
                + "(0) max = " + max + "(0)";
    }
}
