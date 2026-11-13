package mcport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import mcport.rt.BrowserRuntime;
import net.minecraft.CrashReport;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientBootstrap;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import org.teavm.runtime.Fiber;

/**
 * Reproduces exactly what net.minecraft.client.main.Main does between the last
 * observed boot stage and the point where the browser client stops making progress:
 *
 * <pre>
 *   SharedConstants.tryDetectVersion();
 *   CompletableFuture&lt;?&gt; f = DataFixers.optimize(DataFixTypes.TYPES_FOR_LEVEL_LIST);
 *   CrashReport.preload();
 *   Bootstrap.bootStrap();
 *   ClientBootstrap.bootstrap();
 *   Util.startTimerHackThread();            // daemon thread that sleeps ~forever
 *   Thread.currentThread().setName("Render thread");
 *   f.join();                               // &lt;-- Main.main blocks here
 * </pre>
 *
 * A watchdog dumps every live fiber every two seconds, so a stall shows exactly
 * which fiber is suspended where and which one never resumes.
 */
public final class Probe {
    public static void main(String[] args) {
        startWatchdog();
        try {
            BrowserRuntime.report("stage", "probe-enter");
            SharedConstants.tryDetectVersion();
            BrowserRuntime.report("stage", "probe-version-detected");

            long t0 = System.nanoTime();
            CompletableFuture<?> future = DataFixers.optimize(DataFixTypes.TYPES_FOR_LEVEL_LIST);
            BrowserRuntime.report("stage", "probe-datafixer-created");

            CrashReport.preload();
            BrowserRuntime.report("stage", "probe-crashreport-preloaded");
            Bootstrap.bootStrap();
            BrowserRuntime.report("stage", "probe-bootstrap-done");
            ClientBootstrap.bootstrap();
            BrowserRuntime.report("stage", "probe-client-bootstrap-done");

            Util.startTimerHackThread();
            BrowserRuntime.report("stage", "probe-timer-hack-started");
            Thread.currentThread().setName("Render thread");
            BrowserRuntime.report("fibers", Fiber.dump());

            BrowserRuntime.report("stage", "probe-join-enter");
            future.join();
            BrowserRuntime.report("datafixer-join", "ok " + ((System.nanoTime() - t0) / 1_000_000) + "ms");
            BrowserRuntime.report("fibers", Fiber.dump());
            BrowserRuntime.report("stage", "probe-complete");
        } catch (Throwable t) {
            BrowserRuntime.report("stage", "probe-failed");
            BrowserRuntime.report("error", BrowserRuntime.describe(t));
            BrowserRuntime.report("fibers", Fiber.dump());
        }
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
