package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** java.util.concurrent.FutureTask for TeaVM green threads (monitor-based). */
public class TFutureTask<V> implements RunnableFuture<V> {
    private static final int NEW = 0;
    private static final int RUNNING = 1;
    private static final int DONE = 2;
    private static final int FAILED = 3;
    private static final int CANCELLED = 4;

    private Callable<V> callable;
    private int state = NEW;
    private V result;
    private Throwable failure;
    private Thread runner;

    public TFutureTask(Callable<V> callable) {
        if (callable == null) {
            throw new NullPointerException();
        }
        this.callable = callable;
    }

    public TFutureTask(Runnable runnable, V result) {
        if (runnable == null) {
            throw new NullPointerException();
        }
        this.callable = () -> {
            runnable.run();
            return result;
        };
    }

    @Override
    public void run() {
        Callable<V> c;
        synchronized (this) {
            if (state != NEW) {
                return;
            }
            state = RUNNING;
            runner = Thread.currentThread();
            c = callable;
        }
        try {
            set(c.call());
        } catch (Throwable t) {
            setException(t);
        }
    }

    protected boolean runAndReset() {
        Callable<V> c;
        synchronized (this) {
            if (state != NEW) {
                return false;
            }
            state = RUNNING;
            c = callable;
        }
        try {
            c.call();
        } catch (Throwable t) {
            setException(t);
            return false;
        }
        synchronized (this) {
            if (state == RUNNING) {
                state = NEW;
                return true;
            }
            return false;
        }
    }

    protected void set(V v) {
        synchronized (this) {
            if (state == DONE || state == FAILED || state == CANCELLED) {
                return;
            }
            result = v;
            state = DONE;
            finishLocked();
        }
        done();
    }

    protected void setException(Throwable t) {
        synchronized (this) {
            if (state == DONE || state == FAILED || state == CANCELLED) {
                return;
            }
            failure = t;
            state = FAILED;
            finishLocked();
        }
        done();
    }

    private void finishLocked() {
        runner = null;
        callable = null;
        notifyAll();
    }

    protected void done() {
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        Thread r;
        synchronized (this) {
            if (state != NEW && state != RUNNING) {
                return false;
            }
            r = runner;
            state = CANCELLED;
            finishLocked();
        }
        if (mayInterruptIfRunning && r != null) {
            r.interrupt();
        }
        done();
        return true;
    }

    @Override
    public synchronized boolean isCancelled() {
        return state == CANCELLED;
    }

    @Override
    public synchronized boolean isDone() {
        return state >= DONE;
    }

    @Override
    public synchronized V get() throws InterruptedException, ExecutionException {
        while (state < DONE) {
            wait();
        }
        return report();
    }

    @Override
    public synchronized V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        while (state < DONE) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                throw new TimeoutException();
            }
            wait(left);
        }
        return report();
    }

    private V report() throws ExecutionException {
        if (state == CANCELLED) {
            throw new CancellationException();
        }
        if (state == FAILED) {
            throw new ExecutionException(failure);
        }
        return result;
    }

    @Override
    public String toString() {
        return super.toString() + "[state=" + state + "]";
    }
}
