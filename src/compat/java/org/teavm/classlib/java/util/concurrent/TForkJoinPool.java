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
 *
 * <p>Workers never park on {@code Object.wait}. The Wasm-GC monitor wait path
 * can suspend a fiber permanently (the browser probe showed a worker still
 * inside {@code ObjectAsync.waitImpl} more than ten minutes after its 60s
 * timeout), which strands every task queued afterwards. Idle workers poll the
 * queue through {@code Thread.sleep}, the timer path that does resume, and
 * stay alive until shutdown so a queued task always has a worker to run it.
 */
public class TForkJoinPool extends AbstractExecutorService {
    public interface ForkJoinWorkerThreadFactory {
        ForkJoinWorkerThread newThread(ForkJoinPool pool);
    }

    public static final ForkJoinWorkerThreadFactory defaultForkJoinWorkerThreadFactory =
            pool -> (ForkJoinWorkerThread) (Object) new TForkJoinWorkerThread(pool) { };

    private static final int IDLE_MIN_MS = 1;
    private static final int IDLE_MAX_MS = 10;
    private static final long STUCK_WARN_MS = 5_000;
    /**
     * Real ForkJoinPool compensates for a worker that blocks in join by letting
     * another thread run the dependent task. Minecraft 26.3 worldgen relies on
     * that: every worker can be inside Blender.of -> readChunk(...).join() while
     * the upgradeChunk stage is queued back onto this same pool. Capping workers
     * at parallelism then wedges the server forever, so allow a bounded amount
     * of oversubscription and retire the extras once they stay idle.
     */
    private static final int MAX_WORKERS = 64;
    private static final long EXTRA_WORKER_IDLE_MS = 5_000;

    private static TForkJoinPool common;

    private final Object lock = new Object();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final int parallelism;
    private final ForkJoinWorkerThreadFactory factory;
    private final Thread.UncaughtExceptionHandler handler;
    private int workers;
    private int idle;
    private int active;
    private int nextIndex;
    private boolean shutdown;
    private long oldestQueuedAt;
    private boolean watchdogStarted;
    private long lastStuckLogAt;
    private int stuckLogs;

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
        boolean startWatchdog;
        synchronized (lock) {
            if (shutdown) {
                throw new RejectedExecutionException();
            }
            if (tasks.isEmpty()) {
                oldestQueuedAt = System.currentTimeMillis();
            }
            tasks.addLast(task);
            // An idle worker polls every few milliseconds, so it will pick the
            // task up on its own. Create another worker when the queued work
            // exceeds the number of workers able to take it, even beyond
            // parallelism: a worker blocked in CompletableFuture.join cannot
            // run the dependent task queued here (worldgen does this a lot).
            spawn = workers < MAX_WORKERS && tasks.size() > idle;
            if (spawn) {
                workers++;
            }
            startWatchdog = !watchdogStarted;
            watchdogStarted = true;
        }
        if (spawn) {
            startWorker();
        }
        if (startWatchdog) {
            startWatchdog();
        }
    }

    private void startWorker() {
        ForkJoinWorkerThread t;
        try {
            t = factory.newThread((ForkJoinPool) (Object) this);
        } catch (Throwable failure) {
            synchronized (lock) {
                workers--;
            }
            throw failure;
        }
        TForkJoinWorkerThread w = (TForkJoinWorkerThread) (Object) t;
        synchronized (lock) {
            w.poolIndex = nextIndex++;
        }
        try {
            t.start();
        } catch (Throwable failure) {
            synchronized (lock) {
                workers--;
            }
            throw failure;
        }
    }

    private void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException ignored) {
                    // keep watching
                }
                reportIfStuck();
                synchronized (lock) {
                    if (shutdown && workers == 0) {
                        return;
                    }
                }
            }
        }, "mc-pool-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void reportIfStuck() {
        int queued;
        int running;
        int live;
        long queuedAt;
        synchronized (lock) {
            queued = tasks.size();
            running = active;
            live = workers;
            queuedAt = oldestQueuedAt;
        }
        if (queued == 0 || live == 0) {
            return;
        }
        long age = System.currentTimeMillis() - queuedAt;
        if (age < STUCK_WARN_MS) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (now - lastStuckLogAt < STUCK_WARN_MS || stuckLogs >= 12) {
                return;
            }
            lastStuckLogAt = now;
            stuckLogs++;
        }
        String message = "[MW_STUCK] pool task queued " + age + "ms"
                + " queued=" + queued + " active=" + running + " workers=" + live
                + " parallelism=" + parallelism;
        try {
            mcport.rt.BrowserRuntime.log(message);
            mcport.rt.BrowserRuntime.log("[MW_STUCK] " + mcport.rt.BrowserRuntime.captureStack());
        } catch (Throwable ignored) {
            // diagnostics must never take the pool down
        }
    }

    void runWorker(TForkJoinWorkerThread w) {
        int emptyPolls = 0;
        long idleSince = 0;
        while (true) {
            Runnable task;
            boolean retire = false;
            synchronized (lock) {
                if (shutdown && tasks.isEmpty()) {
                    return;
                }
                task = tasks.pollFirst();
                if (task == null) {
                    idle++;
                    if (idleSince == 0) {
                        idleSince = System.currentTimeMillis();
                    }
                    // Compensation workers exist only to unblock joins. Keep
                    // them around briefly in case another dependent task is
                    // queued, then shrink back to the configured parallelism.
                    if (workers > parallelism
                            && System.currentTimeMillis() - idleSince >= EXTRA_WORKER_IDLE_MS) {
                        // workerExited() owns the worker-count decrement.
                        idle--;
                        retire = true;
                    }
                } else {
                    active++;
                    idleSince = 0;
                    if (tasks.isEmpty()) {
                        oldestQueuedAt = System.currentTimeMillis();
                    }
                }
            }
            if (retire) {
                return;
            }
            if (task == null) {
                try {
                    Thread.sleep(idleDelay(emptyPolls++));
                } catch (InterruptedException ignored) {
                    // re-check the queue
                }
                synchronized (lock) {
                    idle--;
                }
                continue;
            }
            emptyPolls = 0;
            runTask(w, task);
        }
    }

    private void runTask(TForkJoinWorkerThread w, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            if (handler != null) {
                handler.uncaughtException(w, t);
            } else {
                t.printStackTrace();
            }
        } finally {
            synchronized (lock) {
                active--;
                if (tasks.isEmpty()) {
                    oldestQueuedAt = System.currentTimeMillis();
                }
            }
        }
    }

    /**
     * Runs one queued task on the calling pool worker, mirroring ForkJoinPool's
     * helping behaviour when a worker blocks in join. Called from the polling
     * join loops so a dependent stage queued behind the join can still run.
     */
    static boolean helpRunOne() {
        Thread current = Thread.currentThread();
        if (!(current instanceof TForkJoinWorkerThread)) {
            return false;
        }
        return ((TForkJoinWorkerThread) current).pool.runOneTask();
    }

    private boolean runOneTask() {
        Thread current = Thread.currentThread();
        if (!(current instanceof TForkJoinWorkerThread)) {
            return false;
        }
        Runnable task;
        synchronized (lock) {
            task = tasks.pollFirst();
            if (task == null) {
                return false;
            }
            active++;
            if (tasks.isEmpty()) {
                oldestQueuedAt = System.currentTimeMillis();
            }
        }
        runTask((TForkJoinWorkerThread) current, task);
        return true;
    }

    private static long idleDelay(int emptyPolls) {
        if (emptyPolls <= 0) {
            return IDLE_MIN_MS;
        }
        long delay = IDLE_MIN_MS << Math.min(emptyPolls, 4);
        return Math.min(delay, IDLE_MAX_MS);
    }

    void workerExited() {
        boolean respawn = false;
        synchronized (lock) {
            workers--;
            if (!shutdown && !tasks.isEmpty() && idle == 0 && workers < parallelism) {
                workers++;
                respawn = true;
            }
        }
        if (respawn) {
            try {
                startWorker();
            } catch (Throwable ignored) {
                // startWorker already released the worker slot on failure
            }
        }
    }

    public boolean isQuiescent() {
        synchronized (lock) {
            return tasks.isEmpty() && active == 0;
        }
    }

    @Override
    public void shutdown() {
        if (this == common) {
            return;
        }
        synchronized (lock) {
            shutdown = true;
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        synchronized (lock) {
            shutdown = true;
            List<Runnable> left = new ArrayList<>(tasks);
            tasks.clear();
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
        while (true) {
            synchronized (lock) {
                if (shutdown && workers == 0) {
                    return true;
                }
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return false;
            }
            Thread.sleep(Math.min(left, 10));
        }
    }
}
