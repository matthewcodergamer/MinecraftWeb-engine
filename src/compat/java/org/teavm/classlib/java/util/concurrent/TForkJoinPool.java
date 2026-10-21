package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * ForkJoinPool on TeaVM green threads: a FIFO work queue drained by up to
 * {@code parallelism} worker threads created through the supplied factory
 * (so subclasses such as Minecraft's named workers are honoured).
 */
public class TForkJoinPool extends AbstractExecutorService {
    public interface ForkJoinWorkerThreadFactory {
        ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    public static final ForkJoinWorkerThreadFactory defaultForkJoinWorkerThreadFactory =
            pool -> (ForkJoinWorkerThread) (Object) new TForkJoinWorkerThread(pool) { };

    private static TForkJoinPool common;

    private final Object lock = new Object();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final int parallelism;
    private final ForkJoinWorkerThreadFactory factory;
    private final Thread.UncaughtExceptionHandler handler;
    private int workers;
    private int idle;
    private int nextIndex;
    private boolean shutdown;

    public TForkJoinPool() {
        this(Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    public TForkJoinPool(int parallelism) {
        this(parallelism, defaultForkJoinWorkerThreadFactory, null, false);
    }

    public TForkJoinPool(int parallelism, ForkJoinWorkerThreadFactory factory,
            Thread.UncaughtExceptionHandler handler, boolean asyncMode) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException();
        }
        this.parallelism = parallelism;
        this.factory = factory;
        this.handler = handler;
    }

    public static ForkJoinPool commonPool() {
        if (common == null) {
            common = new TForkJoinPool(2);
        }
        return (ForkJoinPool) (Object) common;
    }

    public static int getCommonPoolParallelism() {
        return 2;
    }

    public int getParallelism() {
        return parallelism;
    }

    public Thread.UncaughtExceptionHandler getUncaughtExceptionHandler() {
        return handler;
    }

    public ForkJoinWorkerThreadFactory getFactory() {
        return factory;
    }

    @Override
    public void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException();
        }
        boolean spawn;
        synchronized (lock) {
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            tasks.addLast(task);
            spawn = idle == 0 && workers < parallelism;
            if (spawn) {
                workers++;
            } else {
                lock.notify();
            }
        }
        if (spawn) {
            ForkJoinWorkerThread t = factory.newThread((ForkJoinPool) (Object) this);
            TForkJoinWorkerThread w = (TForkJoinWorkerThread) (Object) t;
            synchronized (lock) {
                w.poolIndex = nextIndex++;
            }
            t.start();
        }
    }

    void runWorker(TForkJoinWorkerThread w) {
        while (true) {
            Runnable task;
            synchronized (lock) {
                while (tasks.isEmpty()) {
                    if (shutdown) {
                        return;
                    }
                    idle++;
                    try {
                        lock.wait(60_000);
                    } catch (InterruptedException e) {
                        // re-check
                    } finally {
                        idle--;
                    }
                    if (tasks.isEmpty() && !shutdown) {
                        return;
                    }
                }
                task = tasks.pollFirst();
            }
            try {
                task.run();
            } catch (Throwable t) {
                if (handler != null) {
                    handler.uncaughtException(w, t);
                } else {
                    t.printStackTrace();
                }
            }
        }
    }

    void workerExited() {
        synchronized (lock) {
            workers--;
            lock.notifyAll();
        }
    }

    public boolean isQuiescent() {
        synchronized (lock) {
            return tasks.isEmpty() && idle == workers;
        }
    }

    @Override
    public void shutdown() {
        if (this == common) {
            return;
        }
        synchronized (lock) {
            shutdown = true;
            lock.notifyAll();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        synchronized (lock) {
            shutdown = true;
            List<Runnable> left = new ArrayList<>(tasks);
            tasks.clear();
            lock.notifyAll();
            return left;
        }
    }

    @Override
    public boolean isShutdown() {
        synchronized (lock) {
            return shutdown;
        }
    }

    @Override
    public boolean isTerminated() {
        synchronized (lock) {
            return shutdown && workers == 0;
        }
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        synchronized (lock) {
            while (!(shutdown && workers == 0)) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                lock.wait(left);
            }
            return true;
        }
    }
}
