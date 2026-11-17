package mcport;

import mcport.rt.BrowserRuntime;
import net.minecraft.resources.Identifier;

/**
 * Local probe for the Wasm-GC monitor/fiber implementation.
 *
 * Exercises the situations that can strand the real client's render fiber:
 *  A) re-entering a lock after the owning fiber suspended inside the critical section,
 *  B) cross-fiber contention where the owner releases after a timed sleep,
 *  C) current-thread identity after other fibers suspend and resume.
 */
public final class MonitorProbe {
    private static final Object LOCK = new Object();

    static final class Holder implements Runnable {
        @Override
        public void run() {
            synchronized (LOCK) {
                BrowserRuntime.report("worker-holds-lock", "ok");
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                BrowserRuntime.report("worker-inside-name", Thread.currentThread().getName());
                BrowserRuntime.report("monitors-while-held",
                        org.teavm.classlib.java.lang.TObject.describeMonitors());
            }
            BrowserRuntime.report("worker-released-lock", "ok");
        }
    }

    public static void main(String[] args) {
        try {
            // Force full class-info emission; TeaVM 0.16 crashes on tiny programs otherwise.
            BrowserRuntime.report("probe-id", Identifier.withDefaultNamespace("monitor_probe").toString());
            final Thread mainThread = Thread.currentThread();
            BrowserRuntime.report("stage", "monitor-probe-start");

            synchronized (LOCK) {
                BrowserRuntime.report("probe-sync-plain", "ok");
            }

            synchronized (LOCK) {
                BrowserRuntime.report("probe-sync-before-sleep", "ok");
                Thread.sleep(50);
                synchronized (LOCK) {
                    BrowserRuntime.report("probe-sync-reenter-after-sleep", "ok");
                }
            }
            BrowserRuntime.report("stage", "monitor-probe-reenter-ok");

            Thread worker = new Thread(new Holder(), "ProbeHolder");
            worker.setDaemon(true);
            worker.start();

            Thread.sleep(100);
            BrowserRuntime.report("main-name-after-worker-start", Thread.currentThread().getName());
            BrowserRuntime.report("main-identity-after-worker-start",
                    String.valueOf(Thread.currentThread() == mainThread));

            BrowserRuntime.report("main-contending", "ok");
            synchronized (LOCK) {
                BrowserRuntime.report("main-acquired-after-contention", "ok");
            }
            BrowserRuntime.report("stage", "monitor-probe-contention-ok");
            BrowserRuntime.report("monitors", org.teavm.classlib.java.lang.TObject.describeMonitors());
            BrowserRuntime.report("class.tobject", org.teavm.classlib.java.lang.TObject.class.getName()
                    + " id=" + System.identityHashCode(org.teavm.classlib.java.lang.TObject.class));
            BrowserRuntime.report("class.object", Object.class.getName()
                    + " id=" + System.identityHashCode(Object.class));

            worker.join(2000);
            BrowserRuntime.report("main-name-late", Thread.currentThread().getName());
            BrowserRuntime.report("main-identity-late", String.valueOf(Thread.currentThread() == mainThread));
            BrowserRuntime.report("stage", "monitor-probe-done");
        } catch (Throwable t) {
            BrowserRuntime.report("error", BrowserRuntime.describe(t));
        }
    }
}
