package net.minecraft.client.sounds;

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
}
