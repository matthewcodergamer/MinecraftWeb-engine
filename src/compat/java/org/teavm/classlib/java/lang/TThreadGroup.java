package org.teavm.classlib.java.lang;

/**
 * Minimal ThreadGroup for TeaVM's cooperative threads.
 *
 * <p>Netty's {@code FastThreadLocalThread} and {@code DefaultThreadFactory}
 * have ThreadGroup in their constructor signatures even when the group is
 * null. TeaVM has no ThreadGroup implementation, so those classes otherwise
 * fail with a linkage error before the in-memory server channel can start.
 * The browser runtime has one implicit group; this type only carries a name
 * and a parent for API compatibility.
 */
public class TThreadGroup extends TObject {
    private final String name;
    private final TThreadGroup parent;
    private boolean daemon;

    public TThreadGroup(String name) {
        this(null, name);
    }

    public TThreadGroup(TThreadGroup parent, String name) {
        this.parent = parent;
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public TThreadGroup getParent() {
        return parent;
    }

    public boolean isDaemon() {
        return daemon;
    }

    public void setDaemon(boolean daemon) {
        this.daemon = daemon;
    }

    public int activeCount() {
        return 1;
    }

    public int activeGroupCount() {
        return 0;
    }

    public String toString() {
        return "java.lang.ThreadGroup[name=" + name + "]";
    }
}
