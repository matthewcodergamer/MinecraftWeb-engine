package org.teavm.classlib.java.util.concurrent.locks;

import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * java.util.concurrent.locks.LockSupport: per-thread permits.
 *
 * <p>Parking polls the permit set through {@code Thread.sleep}. The monitor
 * wait used previously can suspend a Wasm-GC fiber permanently, and it did so
 * during world load; Thread.sleep resumes through the working async timer path.
 */
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
        }
    }

    private static boolean tryAcquire(Thread me) {
        synchronized (MONITOR) {
            return PERMITS.remove(me);
        }
    }

    private static void parkMillis(Object blocker, long millis) {
        Thread me = Thread.currentThread();
        if (blocker != null) {
            synchronized (MONITOR) {
                BLOCKERS.put(me, blocker);
            }
        }
        try {
            if (tryAcquire(me)) {
                return;
            }
            boolean timed = millis > 0;
            long deadline = timed ? System.currentTimeMillis() + millis : 0L;
            int emptyPolls = 0;
            while (true) {
                if (me.isInterrupted()) {
                    return;
                }
                if (tryAcquire(me)) {
                    return;
                }
                long delay = pollDelay(emptyPolls++);
                if (timed) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        return;
                    }
                    delay = Math.min(delay, left);
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    me.interrupt();
                    return;
                }
            }
        } finally {
            if (blocker != null) {
                synchronized (MONITOR) {
                    BLOCKERS.remove(me);
                }
            }
        }
    }

    private static long pollDelay(int emptyPolls) {
        return Math.min(1L << Math.min(emptyPolls, 3), 8L);
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
}
