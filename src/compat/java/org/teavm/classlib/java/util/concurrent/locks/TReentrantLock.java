package org.teavm.classlib.java.util.concurrent.locks;

import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * Reentrant mutex for TeaVM green threads.
 *
 * <p>Contention is resolved by polling the owner field through
 * {@code Thread.sleep}. Parking on a monitor is not used because the Wasm-GC
 * {@code Object.wait} path can suspend a fiber permanently, which would wedge
 * chunk rendering and world generation.
 */
public class TReentrantLock implements Lock {
    private final Object monitor = new Object();
    private Thread owner;
    private int holds;
    private int waiters;
    private final boolean fair;

    public TReentrantLock() {
        this(false);
    }

    public TReentrantLock(boolean fair) {
        this.fair = fair;
    }

    private static long pollDelay(int emptyPolls) {
        return Math.min(1L << Math.min(emptyPolls, 3), 8L);
    }

    @Override
    public void lock() {
        boolean interrupted = false;
        while (true) {
            try {
                lockInterruptibly();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        Thread me = Thread.currentThread();
        synchronized (monitor) {
            if (owner == me) {
                holds++;
                return;
            }
            waiters++;
        }
        boolean locked = false;
        int emptyPolls = 0;
        try {
            while (!locked) {
                synchronized (monitor) {
                    if (owner == null) {
                        owner = me;
                        holds = 1;
                        locked = true;
                    }
                }
                if (!locked) {
                    Thread.sleep(pollDelay(emptyPolls++));
                }
            }
        } finally {
            synchronized (monitor) {
                waiters--;
            }
        }
    }

    @Override
    public boolean tryLock() {
        Thread me = Thread.currentThread();
        synchronized (monitor) {
            if (owner == me) {
                holds++;
                return true;
            }
            if (owner == null) {
                owner = me;
                holds = 1;
                return true;
            }
            return false;
        }
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        Thread me = Thread.currentThread();
        long deadline = System.currentTimeMillis() + unit.toMillis(time);
        synchronized (monitor) {
            if (owner == me) {
                holds++;
                return true;
            }
            waiters++;
        }
        int emptyPolls = 0;
        try {
            while (true) {
                synchronized (monitor) {
                    if (owner == null) {
                        owner = me;
                        holds = 1;
                        return true;
                    }
                }
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                Thread.sleep(Math.min(left, pollDelay(emptyPolls++)));
            }
        } finally {
            synchronized (monitor) {
                waiters--;
            }
        }
    }

    @Override
    public void unlock() {
        synchronized (monitor) {
            if (owner != Thread.currentThread()) {
                throw new IllegalMonitorStateException();
            }
            if (--holds == 0) {
                owner = null;
            }
        }
    }

    public boolean isHeldByCurrentThread() {
        synchronized (monitor) {
            return owner == Thread.currentThread();
        }
    }

    public boolean isLocked() {
        synchronized (monitor) {
            return owner != null;
        }
    }

    public int getHoldCount() {
        synchronized (monitor) {
            return owner == Thread.currentThread() ? holds : 0;
        }
    }

    public boolean hasQueuedThreads() {
        synchronized (monitor) {
            return waiters > 0;
        }
    }

    public final boolean isFair() {
        return fair;
    }

    @Override
    public Condition newCondition() {
        return (Condition) (Object) new ConditionImpl();
    }

    private final class ConditionImpl implements TCondition {
        private int signals;
        private int waiting;

        @Override
        public void await() throws InterruptedException {
            awaitMillis(-1);
        }

        @Override
        public void awaitUninterruptibly() {
            try {
                awaitMillis(-1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public long awaitNanos(long nanosTimeout) throws InterruptedException {
            long start = System.nanoTime();
            awaitMillis(Math.max(1, nanosTimeout / 1_000_000));
            return nanosTimeout - (System.nanoTime() - start);
        }

        @Override
        public boolean await(long time, TimeUnit unit) throws InterruptedException {
            return awaitMillis(Math.max(1, unit.toMillis(time)));
        }

        @Override
        public boolean awaitUntil(Date deadline) throws InterruptedException {
            return awaitMillis(Math.max(1, deadline.getTime() - System.currentTimeMillis()));
        }

        /** Releases the lock fully, waits for a signal or timeout, then re-acquires. */
        private boolean awaitMillis(long millis) throws InterruptedException {
            Thread me = Thread.currentThread();
            int saved;
            synchronized (monitor) {
                if (owner != me) {
                    throw new IllegalMonitorStateException();
                }
                saved = holds;
                owner = null;
                holds = 0;
                waiting++;
            }
            boolean signalled = false;
            boolean interrupted = false;
            long deadline = millis < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + millis;
            int emptyPolls = 0;
            try {
                while (true) {
                    synchronized (monitor) {
                        if (signals > 0) {
                            signals--;
                            signalled = true;
                            break;
                        }
                    }
                    if (millis >= 0) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) {
                            break;
                        }
                        try {
                            Thread.sleep(Math.min(left, pollDelay(emptyPolls++)));
                        } catch (InterruptedException e) {
                            interrupted = true;
                            break;
                        }
                    } else {
                        try {
                            Thread.sleep(pollDelay(emptyPolls++));
                        } catch (InterruptedException e) {
                            interrupted = true;
                            break;
                        }
                    }
                }
                // Re-acquire the lock before reporting interruption, matching
                // Condition.await semantics.
                emptyPolls = 0;
                while (true) {
                    synchronized (monitor) {
                        if (owner == null) {
                            owner = me;
                            holds = saved;
                            break;
                        }
                    }
                    try {
                        Thread.sleep(pollDelay(emptyPolls++));
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            } finally {
                synchronized (monitor) {
                    waiting--;
                }
            }
            if (interrupted) {
                throw new InterruptedException();
            }
            return signalled;
        }

        @Override
        public void signal() {
            synchronized (monitor) {
                if (signals < waiting) {
                    signals++;
                }
            }
        }

        @Override
        public void signalAll() {
            synchronized (monitor) {
                signals = waiting;
            }
        }
    }
}
