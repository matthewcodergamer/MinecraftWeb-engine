package mcport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import mcport.rt.BrowserRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import org.teavm.runtime.Fiber;
import org.teavm.classlib.java.util.concurrent.TCompletableFuture;

/**
 * Isolates the browser client's boot deadlock, one primitive at a time.
 *
 * <p>The real client stops right after {@code Util.startTimerHackThread()} and never returns from
 * {@code DataFixers.optimize(...).join()}. The trace shows the datafixer worker fiber suspending at
 * {@code java.lang.Object$monitorEnterWait$0} and nothing ever resuming it, while the main fiber
 * sits in {@code Object$waitImpl$1} inside {@code join()}.
 *
 * <p>Each test below therefore exercises exactly one cross-fiber handoff, from the simplest
 * (main sleeps) to the exact failing combination (worker thread + CompletableFuture.join). The
 * watchdog dumps every live fiber every two seconds, so a stall names the test that hung.
 */
public final class Probe {
    private static final Object LOCK = new Object();
    private static final AtomicInteger COUNTER = new AtomicInteger();

    public static void main(String[] args) {
        startWatchdog();
        BrowserRuntime.report("stage", "probe-enter");
        try {
            SharedConstants.tryDetectVersion();
            BrowserRuntime.report("stage", "probe-version-detected");

            testMainSleep();
            testThreadJoin();
            testDirectCompletableFutureJoin();
            testExecutorJoin();
            testExecutorSleepJoin();
            testSynchronizedContention();
            testDataFixers();

            BrowserRuntime.report("stage", "probe-complete");
        } catch (Throwable t) {
            BrowserRuntime.report("stage", "probe-failed");
            BrowserRuntime.report("error", BrowserRuntime.describe(t));
        }
        BrowserRuntime.report("fibers", Fiber.dump());
    }

    /** T1: the main fiber suspends in Thread.sleep and is resumed by the JS timer. */
    private static void testMainSleep() throws InterruptedException {
        BrowserRuntime.report("stage", "probe-t1-main-sleep-enter");
        Thread.sleep(100);
        BrowserRuntime.report("stage", "probe-t1-main-sleep-ok");
    }

    /** T2: a second thread runs and suspends in sleep; main waits in Thread.join(). */
    private static void testThreadJoin() {
        BrowserRuntime.report("stage", "probe-t2-thread-start");
        Thread worker = new Thread(() -> {
            BrowserRuntime.report("stage", "probe-t2-worker-enter");
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
                return;
            }
            BrowserRuntime.report("stage", "probe-t2-worker-slept");
        }, "probe-t2-worker");
        worker.setDaemon(true);
        worker.start();
        BrowserRuntime.report("fibers", Fiber.dump());
        BrowserRuntime.report("stage", "probe-t2-join-enter");
        try {
            worker.join();
        } catch (InterruptedException e) {
            BrowserRuntime.report("error", "t2-interrupted");
        }
        BrowserRuntime.report("stage", "probe-t2-join-ok");
    }

    /** Diagnostic: call the TeaVM TCompletableFuture overlay directly before testing the JDK API mapping. */
    private static void testDirectCompletableFutureJoin() {
        BrowserRuntime.report("stage", "probe-t3-direct-enter");
        TCompletableFuture<Integer> future = new TCompletableFuture<>();
        Thread worker = new Thread(() -> future.complete(42), "probe-t3-direct-worker");
        worker.setDaemon(true);
        worker.start();
        int value = future.join();
        BrowserRuntime.report("stage", "probe-t3-direct-join-ok " + value);
    }

    /** T3: the exact pool used by DataFixers.optimize, with a task that never blocks. */
    private static void testExecutorJoin() {
        BrowserRuntime.report("stage", "probe-t3-enter");
        ExecutorService executor = newExecutor("probe-t3");
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> 42, executor);
        BrowserRuntime.report("stage", "probe-t3-before-join");
        int value = future.join();
        BrowserRuntime.report("stage", "probe-t3-join-ok " + value);
        executor.shutdown();
    }

    /** T4: same pool, but the task suspends in Thread.sleep before completing the future. */
    private static void testExecutorSleepJoin() {
        BrowserRuntime.report("stage", "probe-t4-enter");
        ExecutorService executor = newExecutor("probe-t4");
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            return 7;
        }, executor);
        BrowserRuntime.report("stage", "probe-t4-before-join");
        int value = future.join();
        BrowserRuntime.report("stage", "probe-t4-join-ok " + value);
        executor.shutdown();
    }

    /** T5: two fibers contend for one monitor; each must acquire and release it repeatedly. */
    private static void testSynchronizedContention() {
        BrowserRuntime.report("stage", "probe-t5-enter");
        Thread a = new Thread(() -> hammer(), "probe-t5-a");
        Thread b = new Thread(() -> hammer(), "probe-t5-b");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        BrowserRuntime.report("stage", "probe-t5-started");
        try {
            a.join();
            BrowserRuntime.report("stage", "probe-t5-a-joined");
            b.join();
            BrowserRuntime.report("stage", "probe-t5-b-joined");
        } catch (InterruptedException e) {
            BrowserRuntime.report("error", "t5-interrupted");
        }
        BrowserRuntime.report("stage", "probe-t5-ok count=" + COUNTER.get());
    }

    private static void hammer() {
        for (int i = 0; i < 50; ++i) {
            synchronized (LOCK) {
                COUNTER.incrementAndGet();
            }
            if ((i & 15) == 0) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    /** T6: the real failing call: DataFixers.optimize submits DFU work to a worker fiber. */
    private static void testDataFixers() {
        BrowserRuntime.report("stage", "probe-t6-enter");
        long t0 = System.nanoTime();
        CompletableFuture<?> future = DataFixers.optimize(DataFixTypes.TYPES_FOR_LEVEL_LIST);
        BrowserRuntime.report("stage", "probe-t6-created done=" + future.isDone());
        BrowserRuntime.report("fibers", Fiber.dump());
        BrowserRuntime.report("stage", "probe-t6-before-join");
        future.join();
        BrowserRuntime.report("stage", "probe-t6-join-ok " + ((System.nanoTime() - t0) / 1_000_000) + "ms");
    }

    private static ExecutorService newExecutor(String prefix) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void startWatchdog() {
        try {
            Thread watchdog = new Thread(() -> {
                for (int i = 0; i < 45; ++i) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    BrowserRuntime.report("fibers", Fiber.dump());
                }
            }, "fiber-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            BrowserRuntime.report("stage", "probe-watchdog-started");
        } catch (Throwable t) {
            BrowserRuntime.report("watchdog-error", BrowserRuntime.describe(t));
        }
    }
}
