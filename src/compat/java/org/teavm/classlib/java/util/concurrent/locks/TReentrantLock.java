package org.teavm.classlib.java.util.concurrent.locks;

import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/** Reentrant mutex built on a Java monitor (works with TeaVM green threads). */
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
            try {
                while (owner != null) {
                    monitor.wait();
                }
            } finally {
                waiters--;
            }
            owner = me;
            holds = 1;
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
            try {
                while (owner != null) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        return false;
                    }
                    monitor.wait(left);
                }
            } finally {
                waiters--;
            }
            owner = me;
            holds = 1;
            return true;
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
                monitor.notifyAll();
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
            boolean signalled;
            synchronized (monitor) {
                if (owner != me) {
                    throw new IllegalMonitorStateException();
                }
                int saved = holds;
                owner = null;
                holds = 0;
                waiting++;
                monitor.notifyAll();
                long deadline = millis < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + millis;
                try {
                    while (signals == 0) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) {
                            break;
                        }
                        monitor.wait(millis < 0 ? 0 : left);
                    }
                } finally {
                    signalled = signals > 0;
                    if (signalled) {
                        signals--;
                    }
                    waiting--;
                    while (owner != null) {
                        monitor.wait();
                    }
                    owner = me;
                    holds = saved;
                }
            }
            return signalled;
        }

        @Override
        public void signal() {
            synchronized (monitor) {
                if (signals < waiting) {
                    signals++;
                }
                monitor.notifyAll();
            }
        }

        @Override
        public void signalAll() {
            synchronized (monitor) {
                signals = waiting;
                monitor.notifyAll();
            }
        }
    }
}
