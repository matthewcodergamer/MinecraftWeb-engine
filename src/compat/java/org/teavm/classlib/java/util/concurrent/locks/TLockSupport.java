package org.teavm.classlib.java.util.concurrent.locks;

import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/** java.util.concurrent.locks.LockSupport: per-thread permits on a shared monitor. */
public final class TLockSupport {
    private static final Object MONITOR = new Object();
    private static final Set<Thread> PERMITS = new HashSet<>();
    private static final WeakHashMap<Thread, Object> BLOCKERS = new WeakHashMap<>();

    private TLockSupport() {
    }

    public static void unpark(Thread thread) {
        if (thread == null) {
            return;
        }
        synchronized (MONITOR) {
            PERMITS.add(thread);
            MONITOR.notifyAll();
        }
    }

    private static void parkMillis(Object blocker, long millis) {
        Thread me = Thread.currentThread();
        synchronized (MONITOR) {
            if (blocker != null) {
                BLOCKERS.put(me, blocker);
            }
            try {
                if (!PERMITS.remove(me)) {
                    try {
                        MONITOR.wait(millis);
                    } catch (InterruptedException e) {
                        me.interrupt();
                    }
                    PERMITS.remove(me);
                }
            } finally {
                BLOCKERS.remove(me);
            }
        }
    }

    public static void park() {
        parkMillis(null, 0);
    }

    public static void park(Object blocker) {
        parkMillis(blocker, 0);
    }

    public static void parkNanos(long nanos) {
        parkNanos(null, nanos);
    }

    public static void parkNanos(Object blocker, long nanos) {
        if (nanos > 0) {
            parkMillis(blocker, Math.max(1, nanos / 1_000_000));
        }
    }

    public static void parkUntil(long deadline) {
        parkUntil(null, deadline);
    }

    public static void parkUntil(Object blocker, long deadline) {
        long left = deadline - System.currentTimeMillis();
        if (left > 0) {
            parkMillis(blocker, left);
        }
    }

    public static Object getBlocker(Thread t) {
        synchronized (MONITOR) {
            return BLOCKERS.get(t);
        }
    }

    public static void setCurrentBlocker(Object blocker) {
        synchronized (MONITOR) {
            if (blocker == null) {
                BLOCKERS.remove(Thread.currentThread());
            } else {
                BLOCKERS.put(Thread.currentThread(), blocker);
            }
        }
    }
}
