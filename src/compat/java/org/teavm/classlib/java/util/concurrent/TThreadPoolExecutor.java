package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * ThreadPoolExecutor on TeaVM green threads. Tasks are kept in an internal FIFO
 * (the supplied queue is retained for API compatibility only); workers are
 * started on demand up to maximumPoolSize and retire after keepAlive when idle.
 */
public class TThreadPoolExecutor extends AbstractExecutorService {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final BlockingQueue<Runnable> workQueue;
    private int corePoolSize;
    private int maximumPoolSize;
    private long keepAliveMillis;
    private ThreadFactory threadFactory;
    private volatile int workers;
    private volatile int idle;
    private volatile boolean shutdown;

    public TThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, Thread::new);
    }

    public TThreadPoolExecutor(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
            BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
        if (corePoolSize < 0 || maximumPoolSize <= 0 || maximumPoolSize < corePoolSize || keepAliveTime < 0) {
            throw new IllegalArgumentException();
        }
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveMillis = unit.toMillis(keepAliveTime);
        this.workQueue = workQueue;
        this.threadFactory = threadFactory;
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) {
            throw new NullPointerException();
        }
        if (shutdown) {
            throw new RejectedExecutionException("executor shut down");
        }
        tasks.addLast(command);
        if (idle == 0 && workers < Math.max(1, maximumPoolSize)) {
            workers++;
            Thread t = threadFactory.newThread(this::workerLoop);
            if (t == null) {
                workers--;
                throw new RejectedExecutionException("thread factory returned null");
            }
            t.start();
        }
    }

    private void workerLoop() {
        long deadline = System.currentTimeMillis() + keepAliveMillis;
        while (true) {
            Runnable task = tasks.pollFirst();
            while (task == null) {
                if (shutdown) {
                    workers--;
                    return;
                }
                long left = deadline - System.currentTimeMillis();
                if (workers > corePoolSize && left <= 0) {
                    workers--;
                    return;
                }
                idle++;
                try {
                    // Cooperative Wasm-GC fibers must yield through the event loop;
                    // do not wait on the executor monitor from a worker fiber.
                    Thread.sleep(workers > corePoolSize ? Math.max(1, Math.min(left, 10)) : 1);
                } catch (InterruptedException e) {
                    // Re-check shutdown and queued work after interruption.
                } finally {
                    idle--;
                }
                task = tasks.pollFirst();
            }

            Thread self = Thread.currentThread();
            beforeExecute(self, task);
            Throwable thrown = null;
            try {
                task.run();
            } catch (Throwable t) {
                thrown = t;
            }
            afterExecute(task, thrown);
            if (thrown != null) {
                Thread.UncaughtExceptionHandler h = self.getUncaughtExceptionHandler();
                if (h != null) {
                    h.uncaughtException(self, thrown);
                } else {
                    thrown.printStackTrace();
                }
            }
        }
    }

    protected void beforeExecute(Thread t, Runnable r) {
    }

    protected void afterExecute(Runnable r, Throwable t) {
    }

    protected void terminated() {
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> left = new ArrayList<>(tasks);
        tasks.clear();
        return left;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown && workers == 0 && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long timeoutMillis = unit.toMillis(timeout);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!(shutdown && workers == 0 && tasks.isEmpty())) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return false;
            }
            Thread.sleep(Math.min(left, 10));
        }
        return true;
    }


    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }

    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    public void setThreadFactory(ThreadFactory threadFactory) {
        this.threadFactory = threadFactory;
    }

    public int getPoolSize() {
        synchronized (lock) {
            return workers;
        }
    }

    public int getActiveCount() {
        synchronized (lock) {
            return workers - idle;
        }
    }
}
