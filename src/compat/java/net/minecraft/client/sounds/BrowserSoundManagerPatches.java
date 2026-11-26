package net.minecraft.client.sounds;

import mcport.rt.BrowserRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.teavm.jso.JSBody;

/**
 * Browser replacements for the SoundManager / SoundEngine path.
 *
 * The WebAssembly port has no OpenAL device. Vanilla's sounds.json preparation
 * is still skipped (TeaVM's reflective Gson path traps), but playback is real:
 * the event identifier, pitch, volume and position are forwarded to
 * {@code window.mcAudio.playJava(...)}. The host page resolves the event through
 * the vanilla sounds.json registry, fetches the OGG from the range-addressable
 * sound bank, and mixes it with WebAudio.
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

    /** BODY target for SoundManager.apply – the browser registry lives in JS. */
    public static void apply(
            SoundManager manager,
            SoundManager.Preparations preparations,
            ResourceManager resources,
            ProfilerFiller profiler) {
        // no-op: window.mcAudio owns the sounds.json registry
    }

    /** BODY target for SoundEngine.loadLibrary – never touch OpenAL. */
    public static void loadLibrary(SoundEngine engine) {
        // no-op
    }

    /** BODY target for SoundEngine.reload – keep the OpenAL engine untouched. */
    public static void reload(SoundEngine engine) {
        // no-op
    }

    /** BODY target for SoundEngine.play. */
    public static SoundEngine.PlayResult play(SoundEngine engine, SoundInstance sound) {
        return start(sound, sound == null ? 0 : sound.getDelay());
    }

    /** BODY target for SoundManager.playDelayed. */
    public static void playDelayed(SoundManager manager, SoundInstance sound, int delay) {
        if (sound == null) {
            return;
        }
        start(sound, Math.max(delay, sound.getDelay()));
    }

    /** BODY target for SoundManager.isActive; MusicManager uses this to detect track end. */
    public static boolean isActive(SoundManager manager, SoundInstance sound) {
        return sound != null && isActiveInBrowser(sound.hashCode());
    }

    /** BODY target for SoundManager.stop(SoundInstance). */
    public static void stopOne(SoundManager manager, SoundInstance sound) {
        if (sound != null) {
            stopInBrowser(sound.hashCode());
        }
    }

    /** BODY target for SoundManager.stop(). */
    public static void stopEverything(SoundManager manager) {
        stopAllInBrowser();
    }

    private static SoundEngine.PlayResult start(SoundInstance sound, int delay) {
        if (sound == null) {
            return SoundEngine.PlayResult.NOT_STARTED;
        }
        try {
            float volume = sound.getVolume();
            float pitch = sound.getPitch();
            String event = sound.getIdentifier().toString();
            boolean relative = sound.isRelative();
            boolean looping = sound.isLooping();
            double x = sound.getX();
            double y = sound.getY();
            double z = sound.getZ();
            String source = sound.getSource() == null ? "master" : sound.getSource().getName();

            // getFinalSoundSourceVolume already includes the master slider and
            // the per-category slider. Multiplying here keeps the JS mixer
            // stateless and matches vanilla's audible result.
            float category = 1.0f;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.options != null && sound.getSource() != null) {
                category = minecraft.options.getFinalSoundSourceVolume(sound.getSource());
            }
            volume *= category;

            int id = sound.hashCode();
            boolean started = playInBrowser(
                    event, volume, pitch, x, y, z, relative, source, looping, delay, id);
            if (started) {
                if (!playReported) {
                    playReported = true;
                    BrowserRuntime.report("audio-backend", "webaudio");
                }
                return SoundEngine.PlayResult.STARTED;
            }
            return SoundEngine.PlayResult.NOT_STARTED;
        } catch (Throwable failure) {
            // Audio must never take down the client.
            BrowserRuntime.report("audio-error", String.valueOf(failure));
            return SoundEngine.PlayResult.NOT_STARTED;
        }
    }

    @JSBody(params = {"event", "volume", "pitch", "x", "y", "z", "relative",
            "source", "looping", "delay", "id"}, script = ""
            + "var a = window.mcAudio;"
            + "if (!a || !a.playJava) return false;"
            + "try {"
            + "  return !!a.playJava(String(event), Number(volume), Number(pitch),"
            + "    Number(x), Number(y), Number(z), !!relative, String(source),"
            + "    !!looping, Number(delay), Number(id));"
            + "} catch (e) { console.warn('[audio-java] ' + e); return false; }")
    private static native boolean playInBrowser(
            String event, float volume, float pitch, double x, double y, double z,
            boolean relative, String source, boolean looping, int delay, int id);

    @JSBody(params = "id", script = ""
            + "var a = window.mcAudio;"
            + "return !!(a && a.isJavaActive && a.isJavaActive(Number(id)));")
    private static native boolean isActiveInBrowser(int id);

    @JSBody(params = "id", script = ""
            + "var a = window.mcAudio;"
            + "if (a && a.stopJava) a.stopJava(Number(id));")
    private static native void stopInBrowser(int id);

    @JSBody(script = ""
            + "var a = window.mcAudio;"
            + "if (a && a.stopAllJava) a.stopAllJava();")
    private static native void stopAllInBrowser();
}
