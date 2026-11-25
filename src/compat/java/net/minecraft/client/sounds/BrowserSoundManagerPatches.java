package net.minecraft.client.sounds;

import mcport.rt.BrowserRuntime;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Browser replacements for the SoundManager / SoundEngine path.
 *
 * The WebAssembly browser port has no OpenAL device. Vanilla's sounds.json
 * path and SoundEngine.loadLibrary trap (illegal cast / unimplemented native).
 * We return an empty preparation and make apply / engine start no-ops so the
 * rest of the resource reload can complete and the title screen can be built.
 */
public final class BrowserSoundManagerPatches {
    private static boolean playReported;

    private BrowserSoundManagerPatches() {
    }

    /** BODY target for SoundManager.prepare */
    public static SoundManager.Preparations prepare(
            SoundManager manager,
            ResourceManager resources,
            ProfilerFiller profiler) {
        return new SoundManager.Preparations();
    }

    /** BODY target for SoundManager.apply – ignore empty preparations, do nothing. */
    public static void apply(
            SoundManager manager,
            SoundManager.Preparations preparations,
            ResourceManager resources,
            ProfilerFiller profiler) {
        // no-op: browser has no audio device
    }

    /** BODY target for SoundEngine.loadLibrary – never touch OpenAL. */
    public static void loadLibrary(SoundEngine engine) {
        // no-op
    }

    /** BODY target for SoundEngine.reload – keep engine silent. */
    public static void reload(SoundEngine engine) {
        // no-op
    }

    /**
     * BODY target for SoundEngine.play. The browser has no OpenAL device, but
     * callers (music, subtitles, UI clicks) should still see a successful
     * start instead of NOT_STARTED, which makes the client retry or treat the
     * sound as missing. Nothing is mixed or played: this is the silent backend.
     */
    public static SoundEngine.PlayResult play(SoundEngine engine, SoundInstance sound) {
        if (!playReported) {
            playReported = true;
            BrowserRuntime.report("audio-backend", "noop");
        }
        return SoundEngine.PlayResult.STARTED_SILENTLY;
    }
}
