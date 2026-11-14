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
