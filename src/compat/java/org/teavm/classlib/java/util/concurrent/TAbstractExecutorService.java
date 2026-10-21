package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public abstract class TAbstractExecutorService implements ExecutorService {
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new FutureTask<>(runnable, value);
    }

    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTask<>(callable);
    }

    @Override
    public Future<?> submit(Runnable task) {
        RunnableFuture<Void> f = newTaskFor(task, null);
        execute(f);
        return f;
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        RunnableFuture<T> f = newTaskFor(task, result);
        execute(f);
        return f;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        RunnableFuture<T> f = newTaskFor(task);
        execute(f);
        return f;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> t : tasks) {
            futures.add(submit(t));
        }
        for (Future<T> f : futures) {
            try {
                f.get();
            } catch (ExecutionException | RuntimeException ignored) {
                // reported through the future
            }
        }
        return futures;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> t : tasks) {
            futures.add(submit(t));
        }
        for (Future<T> f : futures) {
            long left = deadline - System.currentTimeMillis();
            try {
                if (left <= 0) {
                    throw new TimeoutException();
                }
                f.get(left, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                for (Future<T> g : futures) {
                    g.cancel(true);
                }
                break;
            } catch (ExecutionException | RuntimeException ignored) {
                // reported through the future
            }
        }
        return futures;
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        ExecutionException last = null;
        for (Callable<T> t : tasks) {
            try {
                return submit(t).get();
            } catch (ExecutionException e) {
                last = e;
            }
        }
        throw last != null ? last : new ExecutionException(new IllegalArgumentException("no tasks"));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        ExecutionException last = null;
        for (Callable<T> t : tasks) {
            try {
                return submit(t).get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                last = e;
            }
        }
        throw last != null ? last : new ExecutionException(new IllegalArgumentException("no tasks"));
    }
}
