package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.ForkJoinPool;

public class TForkJoinWorkerThread extends Thread {
    final TForkJoinPool pool;
    int poolIndex;

    protected TForkJoinWorkerThread(ForkJoinPool pool) {
        super("ForkJoinPool-worker");
        this.pool = (TForkJoinPool) (Object) pool;
    }

    protected TForkJoinWorkerThread(ThreadGroup group, ForkJoinPool pool, boolean preserveThreadLocals) {
        this(pool);
    }

    public ForkJoinPool getPool() {
        return (ForkJoinPool) (Object) pool;
    }

    public int getPoolIndex() {
        return poolIndex;
    }

    protected void onStart() {
    }

    protected void onTermination(Throwable exception) {
    }

    @Override
    public void run() {
        Throwable failure = null;
        try {
            onStart();
            pool.runWorker(this);
        } catch (Throwable t) {
            failure = t;
        } finally {
            try {
                onTermination(failure);
            } finally {
                pool.workerExited();
            }
        }
    }
}
