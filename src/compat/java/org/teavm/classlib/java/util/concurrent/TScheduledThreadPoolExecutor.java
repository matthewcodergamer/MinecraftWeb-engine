package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ScheduledThreadPoolExecutor on TeaVM green threads.
 *
 * <p>TeaVM's class library has no scheduled executors and no JS timer that can
 * be awaited synchronously, so delay handling lives in one green timer thread.
 * It sleeps on a monitor until the next task is due and then hands the task to
 * the ordinary {@link TThreadPoolExecutor} worker pool.</p>
 */
public class TScheduledThreadPoolExecutor extends TThreadPoolExecutor implements TScheduledExecutorService {
    private final Object timerLock = new Object();
    private final List<Task<?>> timers = new ArrayList<>();
    private final ThreadFactory threadFactory;
    private Thread timerThread;
    private boolean timerShutdown;

    public TScheduledThreadPoolExecutor(int corePoolSize) {
        this(corePoolSize, (ThreadFactory) Thread::new);
    }

    public TScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, Integer.MAX_VALUE, 0L, TimeUnit.NANOSECONDS, new LinkedBlockingDeque<>(), threadFactory);
        this.threadFactory = threadFactory == null ? Thread::new : threadFactory;
    }

    public TScheduledThreadPoolExecutor(int corePoolSize, java.util.concurrent.RejectedExecutionHandler handler) {
        this(corePoolSize);
    }

    public TScheduledThreadPoolExecutor(int corePoolSize, ThreadFactory threadFactory,
            java.util.concurrent.RejectedExecutionHandler handler) {
        this(corePoolSize, threadFactory);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        if (command == null) {
            throw new NullPointerException();
        }
        return enqueue(new Task<Void>(this, command, null, unit.toMillis(delay), 0L));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (callable == null) {
            throw new NullPointerException();
        }
        return enqueue(new Task<V>(this, null, callable, unit.toMillis(delay), 0L));
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        if (command == null) {
            throw new NullPointerException();
        }
        return enqueue(new Task<Void>(this, command, null, unit.toMillis(initialDelay),
                Math.max(1L, unit.toMillis(period))));
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        if (command == null) {
            throw new NullPointerException();
        }
        return enqueue(new Task<Void>(this, command, null, unit.toMillis(initialDelay),
                -Math.max(1L, unit.toMillis(delay))));
    }

    private <V> ScheduledFuture<V> enqueue(Task<V> task) {
        synchronized (timerLock) {
            if (timerShutdown) {
                throw new RejectedExecutionException("scheduler shut down");
            }
            timers.add(task);
            if (timerThread == null) {
                timerThread = threadFactory.newThread(this::timerLoop);
                timerThread.setDaemon(true);
                timerThread.start();
            }
            timerLock.notifyAll();
        }
        return task;
    }

    private void timerLoop() {
        while (true) {
            Task<?> due = null;
            synchronized (timerLock) {
                if (timerShutdown) {
                    return;
                }
                long now = System.currentTimeMillis();
                long best = Long.MAX_VALUE;
                for (Task<?> task : timers) {
                    if (task.state == Task.CANCELLED) {
                        continue;
                    }
                    if (task.nextTime < best) {
                        best = task.nextTime;
                        due = task;
                    }
                }
                if (due == null) {
                    try {
                        timerLock.wait();
                    } catch (InterruptedException e) {
                        // Re-check shutdown and the task list.
                    }
                    continue;
                }
                long wait = best - now;
                if (wait > 0L) {
                    try {
                        timerLock.wait(wait);
                    } catch (InterruptedException e) {
                        // Re-check the deadline.
                    }
                    continue;
                }
                timers.remove(due);
            }
            execute(due);
        }
    }

    @Override
    public void shutdown() {
        synchronized (timerLock) {
            timerShutdown = true;
            timerLock.notifyAll();
        }
        super.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        synchronized (timerLock) {
            timerShutdown = true;
            timerLock.notifyAll();
        }
        return super.shutdownNow();
    }

    public boolean getRemoveOnCancelPolicy() {
        return false;
    }

    public void setRemoveOnCancelPolicy(boolean value) {
    }

    public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return false;
    }

    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
    }

    public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return false;
    }

    public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
    }

    public void purge() {
    }

    public BlockingQueue<Runnable> getQueue() {
        return super.getQueue();
    }

    /** One delayed task that is also its own future. */
    static final class Task<V> implements Runnable, ScheduledFuture<V> {
        static final int PENDING = 0;
        static final int RUNNING = 1;
        static final int DONE = 2;
        static final int FAILED = 3;
        static final int CANCELLED = 4;

        private final TScheduledThreadPoolExecutor owner;
        private final Runnable command;
        private final Callable<V> callable;
        /** Absolute time in {@link System#currentTimeMillis()} milliseconds. */
        private long nextTime;
        /** 0 = one shot, &gt;0 = fixed rate, &lt;0 = fixed delay, in milliseconds. */
        private final long period;
        private int state = PENDING;
        private V result;
        private Throwable failure;
        private Thread runner;

        Task(TScheduledThreadPoolExecutor owner, Runnable command, Callable<V> callable, long delay, long period) {
            this.owner = owner;
            this.command = command;
            this.callable = callable;
            this.nextTime = System.currentTimeMillis() + Math.max(0L, delay);
            this.period = period;
        }

        @Override
        public void run() {
            synchronized (this) {
                if (state == CANCELLED || (period == 0L && state != PENDING)) {
                    return;
                }
                state = RUNNING;
                runner = Thread.currentThread();
            }
            V value = null;
            Throwable thrown = null;
            try {
                if (callable != null) {
                    value = callable.call();
                } else {
                    command.run();
                }
            } catch (Throwable t) {
                thrown = t;
            }
            boolean repeat = false;
            synchronized (this) {
                runner = null;
                if (state == CANCELLED) {
                    notifyAll();
                    return;
                }
                if (period > 0L) {
                    nextTime += period;
                    state = PENDING;
                    repeat = true;
                } else if (period < 0L) {
                    nextTime = System.currentTimeMillis() - period;
                    state = PENDING;
                    repeat = true;
                } else {
                    result = value;
                    failure = thrown;
                    state = thrown == null ? DONE : FAILED;
                    notifyAll();
                }
            }
            if (repeat) {
                try {
                    owner.enqueue(this);
                } catch (RejectedExecutionException e) {
                    synchronized (this) {
                        state = CANCELLED;
                        notifyAll();
                    }
                }
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            Thread thread;
            synchronized (owner.timerLock) {
                owner.timers.remove(this);
            }
            synchronized (this) {
                if (state == DONE || state == FAILED || state == CANCELLED) {
                    return false;
                }
                state = CANCELLED;
                thread = runner;
                notifyAll();
            }
            if (mayInterruptIfRunning && thread != null) {
                thread.interrupt();
            }
            return true;
        }

        @Override
        public boolean isCancelled() {
            synchronized (this) {
                return state == CANCELLED;
            }
        }

        @Override
        public boolean isDone() {
            synchronized (this) {
                return state == DONE || state == FAILED || state == CANCELLED;
            }
        }

        @Override
        public V get() throws InterruptedException, ExecutionException {
            synchronized (this) {
                while (state != DONE && state != FAILED && state != CANCELLED) {
                    wait();
                }
                return resultLocked();
            }
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
            synchronized (this) {
                while (state != DONE && state != FAILED && state != CANCELLED) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0L) {
                        throw new TimeoutException();
                    }
                    wait(left);
                }
                return resultLocked();
            }
        }

        private V resultLocked() throws ExecutionException {
            if (state == CANCELLED) {
                throw new CancellationException();
            }
            if (state == FAILED) {
                throw new ExecutionException(failure);
            }
            return result;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long left = nextTime - System.currentTimeMillis();
            return unit.convert(Math.max(0L, left), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) {
                return 0;
            }
            long a = nextTime;
            long b = other instanceof Task ? ((Task<?>) other).nextTime
                    : System.currentTimeMillis() + other.getDelay(TimeUnit.MILLISECONDS);
            return a < b ? -1 : a > b ? 1 : 0;
        }
    }
}
