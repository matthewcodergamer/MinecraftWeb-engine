package mcport.rt;

import java.io.File;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;

/** Crash-report shutdown that tolerates a client constructor failing before SoundManager exists. */
public final class CrashReportBrowser {
    private CrashReportBrowser() {
    }

    public static int saveReportAndShutdownSoundManager(
            Minecraft minecraft, File gameDirectory, CrashReport crash, int exitCode) {
        // TeaVM's Java stack traces are synthetic (Throwable$FakeClass.fakeMethod), but the host
        // JavaScript exception keeps the real Wasm function names. Log them before the report is
        // written: it is the only way to locate a crash inside the optimised client.
        if (crash != null) {
            BrowserRuntime.log("[MinecraftWeb] crash: " + BrowserRuntime.describe(crash.getException()));
        }
        int actualExitCode = Minecraft.saveReport(gameDirectory, crash, exitCode);
        if (minecraft != null) {
            SoundManager soundManager = minecraft.getSoundManager();
            if (soundManager != null) {
                soundManager.emergencyShutdown();
            }
        }
        return actualExitCode;
    }
}
