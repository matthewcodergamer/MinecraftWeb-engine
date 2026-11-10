package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import mcport.rt.jdk.LiteScheduledExecutor;

public final class TExecutors {
    private TExecutors() {
    }

    public static ExecutorService newCachedThreadPool() {
        return newCachedThreadPool(Thread::new);
    }

    public static ExecutorService newCachedThreadPool(ThreadFactory factory) {
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new LinkedBlockingDeque<>(),
                factory);
    }

    public static ExecutorService newFixedThreadPool(int n) {
        return newFixedThreadPool(n, Thread::new);
    }

    public static ExecutorService newFixedThreadPool(int n, ThreadFactory factory) {
        return new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingDeque<>(), factory);
    }

    public static ExecutorService newSingleThreadExecutor() {
        return newFixedThreadPool(1);
    }

    public static ExecutorService newSingleThreadExecutor(ThreadFactory factory) {
        return newFixedThreadPool(1, factory);
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize) {
        return new LiteScheduledExecutor(corePoolSize, Thread::new);
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize, ThreadFactory factory) {
        return new LiteScheduledExecutor(corePoolSize, factory);
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        return new LiteScheduledExecutor(1, Thread::new);
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor(ThreadFactory factory) {
        return new LiteScheduledExecutor(1, factory);
    }

    public static ThreadFactory defaultThreadFactory() {
        return Thread::new;
    }

    public static <T> Callable<T> callable(Runnable task, T result) {
        return () -> {
            task.run();
            return result;
        };
    }

    public static Callable<Object> callable(Runnable task) {
        return callable(task, null);
    }
}
