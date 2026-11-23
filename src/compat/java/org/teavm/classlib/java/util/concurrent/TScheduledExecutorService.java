package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * java.util.concurrent.ScheduledExecutorService for the browser class library.
 *
 * TeaVM 0.16 provides ExecutorService but not the scheduled variants. Netty's
 * EventExecutorGroup extends ScheduledExecutorService, so the interface has to exist
 * even though Netty supplies its own implementation and the browser client never
 * creates a JDK scheduled thread pool.
 */
public interface TScheduledExecutorService extends ExecutorService {
    ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit);

    <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit);

    ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit);

    ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit);
}
