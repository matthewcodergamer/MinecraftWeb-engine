package mcport;

import mcport.rt.BrowserRuntime;
import net.minecraft.resources.Identifier;

/**
 * Stress probe for the cooperative wait/notify implementation.
 *
 * Several fibers take turns entering the same monitor, parking in Object.wait
 * with and without timeouts while other fibers contend for the monitor and call
 * notifyAll. This is the shape that strands the client's render fiber, so if
 * the implementation is wrong it should eventually hang here too - and a
 * watchdog reports exactly which round stalled.
 */
public final class MonitorStressProbe {
    private static final Object LOCK = new Object();
    private static volatile int round;

    static final class Waiter implements Runnable {
        private final int index;

        Waiter(int index) {
            this.index = index;
        }

        @Override
        public void run() {
            for (int i = 0; i < 8; ++i) {
                synchronized (LOCK) {
                    try {
                        if (i % 2 == 0) {
                            LOCK.wait(25);
                        } else {
                            LOCK.wait();
                        }
                    } catch (InterruptedException ignored) {
                    }
                }
                BrowserRuntime.report("waiter-step", index + "/" + i);
            }
            BrowserRuntime.report("waiter-done", String.valueOf(index));
        }
    }

    static final class Notifier implements Runnable {
        @Override
        public void run() {
            for (int i = 0; i < 8; ++i) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                }
                synchronized (LOCK) {
                    LOCK.notifyAll();
                }
                BrowserRuntime.report("notifier-step", String.valueOf(i));
            }
        }
    }

    public static void main(String[] args) {
        try {
            BrowserRuntime.report("probe-id", Identifier.withDefaultNamespace("monitor_stress_probe").toString());

            Thread watchdog = new Thread(() -> {
                for (int i = 0; i < 12; ++i) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    BrowserRuntime.report("stress-monitors",
                            org.teavm.classlib.java.lang.TObject.describeMonitors());
                }
            }, "StressWatchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            Thread[] waiters = new Thread[3];
            for (int i = 0; i < waiters.length; ++i) {
                waiters[i] = new Thread(new Waiter(i), "StressWaiter-" + i);
                waiters[i].setDaemon(true);
                waiters[i].start();
            }
            Thread notifier = new Thread(new Notifier(), "StressNotifier");
            notifier.setDaemon(true);
            notifier.start();

            for (int i = 0; i < 8; ++i) {
                Thread.sleep(60);
                synchronized (LOCK) {
                    LOCK.notifyAll();
                }
                round = i;
                BrowserRuntime.report("main-round", String.valueOf(i));
            }
            for (Thread waiter : waiters) {
                waiter.join(2000);
            }
            notifier.join(2000);
            BrowserRuntime.report("stage", "stress-probe-done");
        } catch (Throwable t) {
            BrowserRuntime.report("error", BrowserRuntime.describe(t));
        }
    }
}
