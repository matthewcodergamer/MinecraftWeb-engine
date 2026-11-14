package mcport;

import mcport.rt.BrowserRuntime;

/** Full client entry: runs Mojang's real net.minecraft.client.main.Main with an offline profile. */
public final class ClientMain {
    public static void main(String[] args) {
        try {
            // Set before Netty PlatformDependent is initialized.
            System.setProperty("io.netty.noUnsafe", "true");
            BrowserRuntime.report("stage", "client-main-enter");
            setOsProperties();
            BrowserRuntime.report("stage", "client-os-properties-ready");
            startFiberWatchdog();
            // The Rust Body mirrors the Java world. This hook is inert until the
            // client has a level and player; it never changes Java game state.
            BrowserRuntime.report("stage", "chunk-bridge-install-enter");
            mcport.bridge.ChunkBridge.install();
            BrowserRuntime.report("stage", "chunk-bridge-installed");
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
            BrowserRuntime.report("trace", "after-mojang-main-main");
            // Main.main() returning is a critical lifecycle boundary in the browser port.
            // Record it explicitly so smoke tests can distinguish a normal return from a stuck call.
            BrowserRuntime.report("stage", "mojang-main-returned");
        } catch (Throwable t) {
            BrowserRuntime.report("stage", "client-main-caught-throwable");
            BrowserRuntime.report("stage", "failed");
            BrowserRuntime.report("error", BrowserRuntime.describe(t));
        }
    }

    /**
     * TeaVM publishes os.name = "TeaVM", which org.lwjgl.system.Platform does not know. The port's
     * browser platform contract is Linux/POSIX (see LwjglPatches#platform and the lib<name>.so naming),
     * so the environment is declared before any consumer class can initialize on "TeaVM" and fail.
     */
    static void setOsProperties() {
        System.setProperty("os.name", "Linux");
        System.setProperty("os.version", "6.6.0-web");
        System.setProperty("os.arch", "aarch64");
        System.setProperty("user.name", "player");
        System.setProperty("user.dir", "/minecraft");
    }

    /** Reports every live TeaVM fiber every 5 s so a boot stall shows which fiber is stuck. */
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
                }
            }, "mc-fiber-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();
            BrowserRuntime.report("stage", "client-fiber-watchdog-started");
        } catch (Throwable t) {
            BrowserRuntime.report("watchdog-error", BrowserRuntime.describe(t));
        }
    }
}
