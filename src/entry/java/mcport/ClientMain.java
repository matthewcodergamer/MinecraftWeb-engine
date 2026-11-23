package mcport;

import mcport.rt.BrowserRuntime;

/** Full client entry: runs Mojang's real net.minecraft.client.main.Main with an offline profile. */
public final class ClientMain {
    // Deliberate attribution fingerprint. Keep this referenced from main() so
    // TeaVM's reachability/dead-code optimization cannot discard the literal.
    private static final String BUILD_ID =
            "Original_Minecraft_Port_by_matthewcodergamer_2026";
    public static final String OWNER_SIGNATURE =
            "Engine_Designed_and_Built_by_Matthew_2026";

    public static void main(String[] args) {
        try {
            BrowserRuntime.report("build-id", BUILD_ID);
            System.out.println(OWNER_SIGNATURE);
            BrowserRuntime.report("owner-signature", OWNER_SIGNATURE);
            // Set before Netty PlatformDependent is initialized.
            System.setProperty("io.netty.noUnsafe", "true");
            // TeaVM 0.16 has no java.util.concurrent.locks.StampedLock, which Netty 4.2's
            // default adaptive allocator needs. The browser does not benefit from pooled
            // direct buffers, so select the unpooled allocator before ByteBufUtil's <clinit>.
            System.setProperty("io.netty.allocator.type", "unpooled");
            BrowserRuntime.report("stage", "client-main-enter");

            setOsProperties();
            BrowserRuntime.report("stage", "client-os-properties-ready");

            startFiberWatchdog();

            // The Rust Body mirrors the Java world. This hook is inert until the
            // client has a level and player; it never changes Java game state.
            BrowserRuntime.report("stage", "chunk-bridge-install-enter");
            mcport.bridge.ChunkBridge.install();
            BrowserRuntime.report("stage", "chunk-bridge-installed");

            probeMemoryStack();

            BrowserRuntime.report("stage", "mojang-main-enter");
            BrowserRuntime.report("trace", "before-mojang-main-main");

            net.minecraft.client.main.Main.main(new String[] {
                    "--version", "26.3",
                    "--gameDir", "/minecraft",
                    "--assetsDir", "/minecraft/assets",
                    "--assetIndex", "26.3",
                    "--accessToken", "0",
                    "--username", "Player",
                    "--userType", "legacy",
                    "--versionType", "release"
            });

            // Main.main() returning is a critical lifecycle boundary in the browser port.
            // Record it explicitly so smoke tests can distinguish a normal return from a stuck call.
            BrowserRuntime.report("trace", "after-mojang-main-main");
            BrowserRuntime.report("stage", "mojang-main-returned");
        } catch (Throwable t) {
            BrowserRuntime.report("stage", "client-main-caught-throwable");
            BrowserRuntime.report("stage", "failed");
            String crash = BrowserRuntime.crashReport();
            BrowserRuntime.report("error", crash != null && !crash.isEmpty()
                    ? crash
                    : BrowserRuntime.describe(t));
        }
    }

    static void setOsProperties() {
        System.setProperty("os.name", "Linux");
        System.setProperty("os.version", "6.6.0-web");
        System.setProperty("os.arch", "aarch64");
        System.setProperty("user.name", "player");
        System.setProperty("user.dir", "/minecraft");
    }

    static void startFiberWatchdog() {
        try {
            Thread watchdog = new Thread(() -> {
                for (int i = 0; i < 120; ++i) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    BrowserRuntime.report("fibers", org.teavm.runtime.Fiber.dump());
                    try {
                        BrowserRuntime.report("monitors", mcport.rt.MonitorRegistry.describe());
                    } catch (Throwable t) {
                        BrowserRuntime.report("monitors", "unavailable: " + BrowserRuntime.describe(t));
                    }
                    try {
                        // A contended enter that never reached the waiter queue can
                        // never be granted by a release. Finish it explicitly after a
                        // grace period so one lost continuation cannot wedge boot.
                        if (mcport.rt.MonitorRegistry.recoverStalled(12000)) {
                            BrowserRuntime.report("monitor-recovery", mcport.rt.MonitorRegistry.describe());
                        }
                    } catch (Throwable t) {
                        BrowserRuntime.report("monitor-recovery", "failed: " + BrowserRuntime.describe(t));
                    }
                }
            }, "mc-fiber-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            BrowserRuntime.report("stage", "client-fiber-watchdog-started");
        } catch (Throwable t) {
            BrowserRuntime.report("watchdog-error", BrowserRuntime.describe(t));
        }
    }

    /**
     * Diagnostic only: reports the state of this thread's LWJGL MemoryStack and performs one
     * allocation, so a "Out of stack space" failure shows the stack's size/address/pointer.
     * Never throws; a failure is reported and boot continues into the real client.
     */
    static void probeMemoryStack() {
        try {
            BrowserRuntime.report("stack-probe", "property stackSize="
                    + System.getProperty("org.lwjgl.system.stackSize"));
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                BrowserRuntime.report("stack-probe", "size=" + stack.getSize()
                        + " address=" + stack.getAddress()
                        + " pointer=" + stack.getPointer());
                java.nio.ByteBuffer text = stack.ASCII("probe", true);
                BrowserRuntime.report("stack-probe", "alloc-ok remaining=" + text.remaining()
                        + " pointerAfter=" + stack.getPointer());
            }
        } catch (Throwable t) {
            BrowserRuntime.report("stack-probe", "FAILED: " + BrowserRuntime.describe(t));
        }
    }
}