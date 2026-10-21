package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public interface TFuture<V> {
    boolean cancel(boolean mayInterruptIfRunning);

    boolean isCancelled();

    boolean isDone();

    V get() throws InterruptedException, ExecutionException;

    V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException;

    default V resultNow() {
        if (!isDone()) {
            throw new IllegalStateException("Task has not completed");
        }
        try {
            return get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    default Throwable exceptionNow() {
        if (!isDone()) {
            throw new IllegalStateException("Task has not completed");
        }
        try {
            get();
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("Task completed with a result");
    }
}
