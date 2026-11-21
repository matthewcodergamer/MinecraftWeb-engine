package mcport;

import mcport.rt.BrowserRuntime;
import net.minecraft.resources.Identifier;

/**
 * Local probe for the exact shape of the client stall: one fiber parks inside
 * Object.wait while holding the lock, and another fiber asks for the same lock.
 *
 * A) timed wait, no notifier        -> must time out and hand the lock over
 * B) wait + notifyAll from another  -> must resume the waiter and hand over
 * C) wait on a lock nobody wants    -> must not strand the waiter
 */
public final class MonitorWaitProbe {
    private static final Object LOCK = new Object();

    static final class Waiter implements Runnable {
        @Override
        public void run() {
            int waitingFiberId = org.teavm.runtime.Fiber.currentId();
            BrowserRuntime.report("waiter-fiber-before", String.valueOf(waitingFiberId));
            synchronized (LOCK) {
                BrowserRuntime.report("waiter-holds", "ok");
                try {
                    LOCK.wait(200);
                } catch (InterruptedException ignored) {
                }
                int resumedFiberId = org.teavm.runtime.Fiber.currentId();
                BrowserRuntime.report("waiter-fiber-after", String.valueOf(resumedFiberId));
                if (waitingFiberId != resumedFiberId) {
                    throw new IllegalStateException("Object.wait resumed on a different fiber: "
                            + waitingFiberId + " -> " + resumedFiberId);
                }
                BrowserRuntime.report("waiter-back-from-wait", Thread.currentThread().getName());
                BrowserRuntime.report("waiter-monitors",
                        org.teavm.classlib.java.lang.TObject.describeMonitors());
            }
            BrowserRuntime.report("waiter-released", "ok");
        }
    }

    public static void main(String[] args) {
        try {
            BrowserRuntime.report("probe-id", Identifier.withDefaultNamespace("monitor_wait_probe").toString());
            Thread waiter = new Thread(new Waiter(), "ProbeWaiter");
            waiter.setDaemon(true);
            waiter.start();

            Thread.sleep(40);
            BrowserRuntime.report("main-wants-lock", "ok");
            synchronized (LOCK) {
                BrowserRuntime.report("main-acquired-after-wait", "ok");
            }
            BrowserRuntime.report("stage", "wait-probe-timed-ok");

            // B: wait + notify
            Thread notifier = new Thread(() -> {
                try {
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {
                }
                synchronized (LOCK) {
                    BrowserRuntime.report("notifier-holds", "ok");
                    LOCK.notifyAll();
                }
                BrowserRuntime.report("notifier-done", "ok");
            }, "ProbeNotifier");
            notifier.setDaemon(true);
            notifier.start();

            Thread.sleep(20);
            synchronized (LOCK) {
                try {
                    LOCK.wait(1000);
                } catch (InterruptedException ignored) {
                }
                BrowserRuntime.report("main-back-from-notify-wait", "ok");
            }
            BrowserRuntime.report("stage", "wait-probe-notify-ok");

            waiter.join(3000);
            BrowserRuntime.report("stage", "wait-probe-done");
        } catch (Throwable t) {
            BrowserRuntime.report("error", BrowserRuntime.describe(t));
        }
    }
}
