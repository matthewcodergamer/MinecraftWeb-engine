package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.TimeUnit;

/**
 * CountDownLatch for TeaVM's cooperative threads.
 *
 * <p>TeaVM's class library has no latch implementation, but Netty's
 * {@code SingleThreadEventExecutor} constructs one unconditionally when the
 * integrated server creates its local event-loop group. Awaiting polls the
 * counter through {@code Thread.sleep}, matching the other concurrency shims
 * in this package; monitor waits can strand a Wasm-GC fiber.
 */
public class TCountDownLatch {
    protected final Object monitor = new Object();
    private volatile int count;

    public TCountDownLatch(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count < 0");
        }
        this.count = count;
    }

    public void await() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        int emptyPolls = 0;
        while (true) {
            synchronized (monitor) {
                if (count == 0) {
                    return;
                }
            }
            Thread.sleep(pollDelay(emptyPolls++));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        long timeoutMillis = unit.toMillis(timeout);
        long deadline = timeoutMillis <= 0 ? 0 : System.currentTimeMillis() + timeoutMillis;
        int emptyPolls = 0;
        while (true) {
            synchronized (monitor) {
                if (count == 0) {
                    return true;
                }
            }
            if (timeoutMillis <= 0) {
                return false;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            Thread.sleep(Math.min(remaining, pollDelay(emptyPolls++)));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    public void countDown() {
        synchronized (monitor) {
            if (count > 0) {
                count--;
            }
        }
    }

    public long getCount() {
        return count;
    }

    public String toString() {
        return super.toString() + "[Count = " + getCount() + "]";
    }

    private static long pollDelay(int emptyPolls) {
        return Math.min(1L << Math.min(emptyPolls, 3), 8L);
    }
}
