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
            // The Rust Body mirrors the Java world. This hook is inert until the
            // client has a level and player; it never changes Java game state.
            BrowserRuntime.report("stage", "chunk-bridge-install-enter");
            mcport.bridge.ChunkBridge.install();
            BrowserRuntime.report("stage", "chunk-bridge-installed");
            BrowserRuntime.report("stage", "mojang-main-enter");
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
}
