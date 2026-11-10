package mcport.rt.jdk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Inline ScheduledExecutorService for the browser port. The real JDK pool drags the whole
 * java.util.concurrent thread-pool implementation into TeaVM's dependency graph (and even a
 * Timer-backed version pulls in the threading machinery). The browser client only needs the
 * auth library's refresh task to exist; offline auth never waits on it, so tasks run inline
 * and scheduling returns a completed future.
 */
public final class LiteScheduledExecutor implements ScheduledExecutorService {
    private volatile boolean shutdown;

    public LiteScheduledExecutor(int corePoolSize, ThreadFactory factory) {
    }

    private static final class Done implements ScheduledFuture<Object> {
        static final Done INSTANCE = new Done();

        @Override public long getDelay(TimeUnit unit) { return 0L; }
        @Override public int compareTo(Delayed other) { return 0; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        @Override public boolean isCancelled() { return false; }
        @Override public boolean isDone() { return true; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
    }

    private ScheduledFuture<Object> run(Runnable task) {
        if (shutdown) throw new java.util.concurrent.RejectedExecutionException("executor is shut down");
        task.run();
        return Done.INSTANCE;
    }

    @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) { return run(command); }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (shutdown) throw new java.util.concurrent.RejectedExecutionException("executor is shut down");
        try {
            callable.call();
        } catch (Exception ignored) {
        }
        return (ScheduledFuture<V>) Done.INSTANCE;
    }

    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) { return run(command); }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) { return run(command); }
    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() { shutdown = true; return Collections.emptyList(); }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        if (shutdown) throw new java.util.concurrent.RejectedExecutionException("executor is shut down");
        try {
            task.call();
        } catch (Exception ignored) {
        }
        return (Future<T>) Done.INSTANCE;
    }

    @Override public <T> Future<T> submit(Runnable task, T result) { return (Future<T>) run(task); }
    @Override public Future<?> submit(Runnable task) { return run(task); }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        List<Future<T>> out = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) out.add(this.<T>submit(task));
        return out;
    }

    @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) { return invokeAll(tasks); }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws ExecutionException {
        for (Callable<T> task : tasks) {
            try {
                return task.call();
            } catch (Exception e) {
                throw new ExecutionException(e);
            }
        }
        throw new ExecutionException(new IllegalStateException("no tasks"));
    }

    @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws ExecutionException { return invokeAny(tasks); }
    @Override public void execute(Runnable command) { run(command); }
}
