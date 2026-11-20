package net.minecraft.client.sounds;

import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Browser replacement for SoundManager.prepare.
 *
 * The WebAssembly browser port has no OpenAL/audio device. Vanilla's sounds.json
 * Gson deserialization path currently traps in TeaVM with an illegal cast before
 * the rest of the client resource reload can complete. Sound preparation is
 * therefore intentionally empty in this target; visual resources continue to
 * load normally and SoundManager.apply receives a valid empty preparation.
 */
public final class BrowserSoundManagerPatches {
    private BrowserSoundManagerPatches() {
    }

    public static SoundManager.Preparations prepare(
            SoundManager manager,
            ResourceManager resources,
            ProfilerFiller profiler) {
        return new SoundManager.Preparations();
    }
}
