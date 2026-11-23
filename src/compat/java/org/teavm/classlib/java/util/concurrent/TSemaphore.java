package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.TimeUnit;

/**
 * Counting semaphore built on a Java monitor (works with TeaVM green threads). TeaVM's classlib
 * has no java.util.concurrent.Semaphore; the integrated server's chunk loading
 * (ChunkMap.createEmptyChunk) throws NoClassDefFoundError without it.
 */
public class TSemaphore {
    private final Object monitor = new Object();
    private int permits;
    private int waiters;
    private final boolean fair;

    public TSemaphore(int permits) {
        this(permits, false);
    }

    public TSemaphore(int permits, boolean fair) {
        this.permits = permits;
        this.fair = fair;
    }

    public void acquire() throws InterruptedException {
        acquire(1);
    }

    private static long pollDelay(int emptyPolls) {
        return Math.min(1L << Math.min(emptyPolls, 3), 8L);
    }

    public void acquire(int count) throws InterruptedException {
        checkCount(count);
        synchronized (monitor) {
            waiters++;
        }
        int emptyPolls = 0;
        try {
            while (true) {
                synchronized (monitor) {
                    if (permits >= count) {
                        permits -= count;
                        return;
                    }
                }
                Thread.sleep(pollDelay(emptyPolls++));
            }
        } finally {
            synchronized (monitor) {
                waiters--;
            }
        }
    }

    public void acquireUninterruptibly() {
        acquireUninterruptibly(1);
    }

    public void acquireUninterruptibly(int count) {
        boolean interrupted = false;
        while (true) {
            try {
                acquire(count);
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    public boolean tryAcquire(int count) {
        checkCount(count);
        synchronized (monitor) {
            if (permits >= count) {
                permits -= count;
                return true;
            }
            return false;
        }
    }

    public boolean tryAcquire(long timeout, TimeUnit unit) throws InterruptedException {
        return tryAcquire(1, timeout, unit);
    }

    public boolean tryAcquire(int count, long timeout, TimeUnit unit) throws InterruptedException {
        checkCount(count);
        long deadline = System.currentTimeMillis() + Math.max(0L, unit.toMillis(timeout));
        synchronized (monitor) {
            waiters++;
        }
        int emptyPolls = 0;
        try {
            while (true) {
                synchronized (monitor) {
                    if (permits >= count) {
                        permits -= count;
                        return true;
                    }
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                Thread.sleep(Math.min(remaining, pollDelay(emptyPolls++)));
            }
        } finally {
            synchronized (monitor) {
                waiters--;
            }
        }
    }

    public void release() {
        release(1);
    }

    public void release(int count) {
        checkCount(count);
        synchronized (monitor) {
            permits += count;
            monitor.notifyAll();
        }
    }

    public int availablePermits() {
        synchronized (monitor) {
            return permits;
        }
    }

    public int drainPermits() {
        synchronized (monitor) {
            int drained = permits;
            permits = 0;
            return drained;
        }
    }

    protected void reducePermits(int reduction) {
        if (reduction < 0) {
            throw new IllegalArgumentException();
        }
        synchronized (monitor) {
            permits -= reduction;
        }
    }

    public boolean isFair() {
        return fair;
    }

    public final boolean hasQueuedThreads() {
        synchronized (monitor) {
            return waiters > 0;
        }
    }

    public final int getQueueLength() {
        synchronized (monitor) {
            return waiters;
        }
    }

    @Override
    public String toString() {
        return super.toString() + "[Permits = " + availablePermits() + "]";
    }

    private static void checkCount(int count) {
        if (count < 0) {
            throw new IllegalArgumentException();
        }
    }
}
