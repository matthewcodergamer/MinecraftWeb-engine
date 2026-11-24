package mcport.bridge;

import java.util.HashMap;
import java.util.Map;

import mcport.rt.BrowserRuntime;
import mcport.rt.FrameLoop;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int32Array;
import org.teavm.jso.typedarrays.Uint8Array;

/**
 * Java Brain -> Rust Body chunk bridge.
 *
 * <p>Java is the only owner of world state. Once per camera tick this hook scans
 * the loaded chunks around the player, packs each non-empty 16×16×16 section as
 * {@code Int32Array(4096)} in {@code y*256 + z*16 + x} order, and hands it to the
 * page. The page forwards it to {@code mc_body::receive_java_chunk}. Rust never
 * generates or mutates blocks; it only meshes what it receives.</p>
 *
 * <p>The hook is installed from {@code mcport.ClientMain}. It is a no-op until a
 * world and player exist, so the title screen and bootstrap are unaffected.</p>
 */
public final class ChunkBridge {
    /** 2 chunks = a 80×80 block area around the player; enough for the demo. */
    private static final int RADIUS = 2;
    /** Full palette scan is intentionally slower than the render tick. */
    private static final long SCAN_INTERVAL_MS = 500L;

    private static final Map<Integer, String> PALETTE = new HashMap<>();
    /** section key -> FNV-1a hash of its 4096 IDs (only non-empty sections). */
    private static final Map<Long, Long> SENT = new HashMap<>();
    /** section key -> FNV-1a hash of its packed sky/block light. */
    private static final Map<Long, Long> SENT_LIGHT = new HashMap<>();
    /**
     * One reusable snapshot buffer. Emission is synchronous into JS (wasm-bindgen
     * copies, or postMessage structured-clones for the iframe), so the next
     * section can overwrite it immediately. This removes ~1.9 MB/s of garbage
     * while the player is exploring.
     */
    private static final Int32Array IDS = Int32Array.create(4096);
    /** Same length as IDS: baked `sky << 4 | block` for one section. */
    private static final Uint8Array LIGHT = Uint8Array.create(4096);

    private static boolean installed;
    private static boolean firstPumpLogged;
    private static boolean bootOverlayCleared;
    private static boolean firstLevelCreatedLogged;
    private static boolean firstLevelPlayerLogged;
    private static boolean firstScanLogged;
    private static String lastScreenButtons = "";
    private static int playerPosReports;
    private static boolean performanceDefaultsApplied;
    private static String lastBootState = "";
    private static long lastBootStateAt;
    private static long lastAdaptiveAt;
    private static int lowFpsStreak;
    private static boolean firstSectionLogged;
    private static boolean ready;
    private static boolean worldRequested;
    private static boolean freshWorld;
    private static boolean rulesApplied;
    /** Wall-clock time the title screen was first observed, or 0 while it is absent. */
    private static long titleSeenAt;
    private static long lastScan;
    /** Identity of the level whose sections have already been sent. */
    private static Object lastLevel;
    /** Identity of the level whose actual generator has already been reported. */
    private static Object lastPresetLevel;

    private ChunkBridge() {
    }

    /** Installs the per-frame hook. Safe to call more than once. */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        BrowserRuntime.report("stage", "chunk-bridge-hook-register");
        FrameLoop.addHook(ChunkBridge::pump);
    }

    /** Drops all cached section hashes (call when a world unloads). */
    public static void reset() {
        SENT.clear();
        SENT_LIGHT.clear();
        PALETTE.clear();
        lastLevel = null;
        lastPresetLevel = null;
        freshWorld = false;
        rulesApplied = false;
        lastScan = 0L;
        titleSeenAt = 0L;
        firstLevelCreatedLogged = false;
        firstLevelPlayerLogged = false;
        firstScanLogged = false;
        lastScreenButtons = "";
        lastBootState = "";
        lastBootStateAt = 0L;
        lastAdaptiveAt = 0L;
        lowFpsStreak = 0;
        playerPosReports = 0;
        emitReset();
    }

    private static void pump() {
        if (!firstPumpLogged) {
            firstPumpLogged = true;
            BrowserRuntime.report("stage", "chunk-bridge-first-frame-hook");
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        reportBootState(minecraft);
        clearBootOverlay(minecraft);
        applyPerformanceDefaults(minecraft);
        adaptQuality(minecraft);
        maybeStartWorld(minecraft);
        if (minecraft.level == null || minecraft.player == null) {
            if (minecraft.level != null && !firstLevelCreatedLogged) {
                firstLevelCreatedLogged = true;
                BrowserRuntime.report("stage", "world-level-created");
            }
            return;
        }
        if (!firstLevelPlayerLogged) {
            firstLevelPlayerLogged = true;
            BrowserRuntime.report("stage", "world-level-and-player-ready");
        }

        // Worlds created through Java's own menus never pass through
        // presetDimensions(), so report what the integrated server actually
        // generated. This is the end-to-end signal the title-screen test uses
        // to prove that selecting Superflat in CreateWorldScreen produced a
        // flat world rather than merely changing a button label.
        if (minecraft.level != lastPresetLevel) {
            if (reportActualPreset(minecraft)) {
                lastPresetLevel = minecraft.level;
            }
        }

        playerPosReports++;
        if (playerPosReports % 20 == 1) {
            BrowserRuntime.report("player-pos",
                    minecraft.player.getX() + "," + minecraft.player.getY() + "," + minecraft.player.getZ()
                            + "," + minecraft.player.getYRot() + "," + minecraft.player.getXRot());
            BlockPos below = minecraft.player.blockPosition().below();
            BrowserRuntime.report("block-below",
                    minecraft.level.getBlockState(below) + "@" + below.getX() + "," + below.getY() + "," + below.getZ());
        }

        // Options that are not part of LevelSettings/WorldOptions are applied
        // as gamerules once the integrated server has a player to run them.
        if (freshWorld && !rulesApplied) {
            rulesApplied = true;
            applyGameRules(minecraft);
        }

        // Loading a different world (or returning to the title screen and picking
        // another save) must not leave the previous world's geometry in the Body.
        if (minecraft.level != lastLevel) {
            lastLevel = minecraft.level;
            SENT.clear();
            SENT_LIGHT.clear();
            emitReset();
        }

        // The Rust camera follows Java's collision/gravity result. Send the eye
        // position every frame; sections are scanned on the slower timer.
        sendPlayer(minecraft.player.getX(), minecraft.player.getEyeY(), minecraft.player.getZ(),
                minecraft.player.getYRot(), minecraft.player.getXRot());
        // Keep the renderer-only sky in lockstep with Java's world clock.
        double day = Math.floorMod(minecraft.level.getOverworldClockTime(), 24000L) / 24000.0;
        emitTime(day);

        long now = System.currentTimeMillis();
        if (now - lastScan < SCAN_INTERVAL_MS) {
            return;
        }
        lastScan = now;
        if (!firstScanLogged) {
            firstScanLogged = true;
            BrowserRuntime.report("stage", "first-loaded-chunk-scan");
        }

        int pcx = minecraft.player.chunkPosition().x();
        int pcz = minecraft.player.chunkPosition().z();
        for (int dz = -RADIUS; dz <= RADIUS; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                LevelChunk chunk = minecraft.level.getChunk(pcx + dx, pcz + dz);
                if (chunk != null) {
                    captureChunk(chunk);
                }
            }
        }
    }

    /**
     * Reports what the client is showing once per rendered frame. The browser smoke
     * test only sees the iframe's {@code __mc.stage}; this heartbeat distinguishes
     * "still reloading" from "reload finished but an onboarding screen is up".
     */
    private static void reportBootState(Minecraft minecraft) {
        Object overlay = minecraft.gui.overlay();
        Object screen = minecraft.gui.screen();
        // Screen widgets can change without the boot state changing (tab switch,
        // text field focus), so publish their bounds independently.
        reportScreenButtons(minecraft);
        String value = "overlay=" + (overlay == null ? "null" : overlay.getClass().getName())
                + " screen=" + (screen == null ? "null" : screen.getClass().getName())
                + " loadFinished=" + minecraft.isGameLoadFinished()
                + " worldAuto=" + worldAuto()
                + " worldRequested=" + hostWorldRequested()
                + " level=" + (minecraft.level != null)
                + " player=" + (minecraft.player != null)
                + " server=" + describeServer(minecraft)
                + " conn=" + (minecraft.getConnection() != null)
                + " sent=" + SENT.size();
        long now = System.currentTimeMillis();
        // Publish on change at most four times a second. The old every-fifth-
        // frame write kept a fresh string in window.__mc and in the console
        // forever, which costs memory in a long browser session.
        if (value.equals(lastBootState) || now - lastBootStateAt < 250) {
            return;
        }
        lastBootState = value;
        lastBootStateAt = now;
        BrowserRuntime.report("boot-state", value);
    }

    /**
     * The Java boot overlay is only removed by its own fade-out render path.
     * World creation can start while that fade is still pending, and then
     * {@code Minecraft.doWorldLoad} spins on {@code gui.overlay() != null}
     * without ever rendering the overlay again. The host page owns the one
     * visible boot loader, so once the game reports that resources are loaded,
     * drop the Java overlay on the normal frame path as well. This also covers
     * worlds created by clicking through Java's own menus.
     */
    private static void clearBootOverlay(Minecraft minecraft) {
        if (bootOverlayCleared || !minecraft.isGameLoadFinished()) {
            return;
        }
        try {
            Object overlay = minecraft.gui.overlay();
            if (overlay == null) {
                bootOverlayCleared = true;
                return;
            }
            if (overlay instanceof net.minecraft.client.gui.screens.LoadingOverlay) {
                minecraft.gui.setOverlay(null);
                bootOverlayCleared = true;
                BrowserRuntime.report("stage", "java-boot-overlay-cleared");
            }
        } catch (Throwable t) {
            bootOverlayCleared = true;
            BrowserRuntime.report("overlay-clear-error", t.getClass().getName());
        }
    }

    /**
     * Server readiness is the other half of {@code Minecraft.doWorldLoad}'s wait
     * loop. Publishing it separates "worldgen still running" from "the client
     * never connected" when the smoke test times out.
     */
    private static String describeServer(Minecraft minecraft) {
        try {
            net.minecraft.client.server.IntegratedServer server = minecraft.getSingleplayerServer();
            if (server == null) {
                return "null";
            }
            return "ready=" + server.isReady() + " tick=" + server.getTickCount();
        } catch (Throwable t) {
            return "error=" + t.getClass().getSimpleName();
        }
    }

    /**
     * Browser performance defaults. A phone has far less GPU memory and fill
     * rate than the desktop client assumes, so start at a render distance that
     * can actually hold 30 FPS and use the fast graphics preset. The host can
     * override with ?rd=N (2..32).
     */
    private static void applyPerformanceDefaults(Minecraft minecraft) {
        if (performanceDefaultsApplied) {
            return;
        }
        performanceDefaultsApplied = true;
        try {
            boolean mobile = isMobileBrowser();
            int rd = requestedRenderDistance();
            if (rd <= 0) {
                rd = mobile ? 8 : 12;
            }
            rd = Math.max(2, Math.min(32, rd));
            if (mobile) {
                try {
                    net.minecraft.client.GraphicsPreset.FAST.apply(minecraft);
                } catch (Throwable ignored) {
                }
                try {
                    minecraft.options.particles().set(net.minecraft.server.level.ParticleStatus.DECREASED);
                } catch (Throwable ignored) {
                }
                try {
                    minecraft.options.entityShadows().set(Boolean.FALSE);
                } catch (Throwable ignored) {
                }
                try {
                    minecraft.options.cloudStatus().set(net.minecraft.client.CloudStatus.FAST);
                } catch (Throwable ignored) {
                }
            }
            minecraft.options.renderDistance().set(rd);
            minecraft.options.simulationDistance().set(Math.min(rd, mobile ? 6 : 8));
            BrowserRuntime.report("perf-defaults", "rd=" + rd + " mobile=" + mobile);
        } catch (Throwable t) {
            BrowserRuntime.report("perf-defaults-error", t.getClass().getName());
        }
    }

    @JSBody(script = "try { return /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent)"
            + " || (navigator.maxTouchPoints > 1 && Math.min(screen.width, screen.height) < 900); }"
            + " catch (e) { return false; }")
    private static native boolean isMobileBrowser();

    @JSBody(script = "try { return Number(new URLSearchParams(location.search).get('rd')) || 0; }"
            + " catch (e) { return 0; }")
    private static native int requestedRenderDistance();

    @JSBody(script = "try { return (window.__mcFrames && window.__mcFrames.fps) || 0; }"
            + " catch (e) { return 0; }")
    private static native double currentFps();

    @JSBody(script = "try { return new URLSearchParams(location.search).get('adaptive') !== '0'; }"
            + " catch (e) { return true; }")
    private static native boolean adaptiveQuality();

    /**
     * Lowers render distance when the measured frame rate stays below 28 FPS.
     * Browsers do not expose a reliable GPU tier, and a phone that is fast at
     * the title screen can drop to single digits once chunks stream in. Three
     * consecutive slow samples (15 s) step the distance down by one, to a floor
     * of 4; quality never steps back up, which avoids an oscillation between
     * "looks nice" and "playable".
     */
    private static void adaptQuality(Minecraft minecraft) {
        if (minecraft.level == null || !adaptiveQuality()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAdaptiveAt < 5000L) {
            return;
        }
        lastAdaptiveAt = now;
        double fps = currentFps();
        if (fps <= 0) {
            return;
        }
        int rd = minecraft.options.renderDistance().get();
        if (fps < 28 && rd > 4) {
            if (++lowFpsStreak >= 3) {
                lowFpsStreak = 0;
                rd--;
                minecraft.options.renderDistance().set(rd);
                BrowserRuntime.report("adaptive-rd",
                        "fps=" + Math.round(fps) + " rd=" + rd);
            }
        } else {
            lowFpsStreak = 0;
        }
    }

    /**
     * Publishes the real Java menu widgets and their GUI coordinates. The CDP
     * test uses these bounds to click the actual Singleplayer/Create World
     * buttons instead of guessing a pixel position.
     */
    private static void reportScreenButtons(Minecraft minecraft) {
        Object screen = minecraft.gui.screen();
        if (!(screen instanceof net.minecraft.client.gui.screens.Screen)) {
            return;
        }
        String name = screen.getClass().getName();
        if (!(screen instanceof TitleScreen)
                && !name.endsWith("SelectWorldScreen")
                && !name.endsWith("CreateWorldScreen")) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("screen=").append(name);
        try {
            com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
            sb.append(" scale=").append(window.getGuiScale());
            // getScreenWidth()/getScreenHeight() are the logical (CSS pixel)
            // window size. getWidth()/getHeight() are framebuffer pixels.
            sb.append(" window=").append(window.getScreenWidth()).append('x').append(window.getScreenHeight());
            sb.append(" gui=").append(window.getGuiScaledWidth()).append('x').append(window.getGuiScaledHeight());
            collectWidgets(screen, sb, 0);
        } catch (Throwable t) {
            sb.append(" error=").append(t.getClass().getName());
        }
        String value = sb.toString();
        if (!value.equals(lastScreenButtons)) {
            lastScreenButtons = value;
            BrowserRuntime.report("screen-buttons", value);
        }
    }

    /**
     * Walks container widgets (tab bars, scroll areas) as well as the screen's
     * direct children, so tab buttons and tab content are clickable by label.
     */
    private static void collectWidgets(Object node, StringBuilder sb, int depth) {
        if (depth > 8) {
            return;
        }
        if (node instanceof AbstractWidget) {
            AbstractWidget widget = (AbstractWidget) node;
            sb.append(" | ").append(widget.getMessage().getString().replace('|', '/'))
                    .append('@').append(widget.getX()).append(',').append(widget.getY())
                    .append(',').append(widget.getWidth()).append(',').append(widget.getHeight());
        }
        if (node instanceof net.minecraft.client.gui.components.events.ContainerEventHandler) {
            for (Object child : ((net.minecraft.client.gui.components.events.ContainerEventHandler) node).children()) {
                if (child != node) {
                    collectWidgets(child, sb, depth + 1);
                }
            }
        }
    }

    /**
     * Creates (or opens) the local world once the title screen is idle and the
     * host page has asked for a world. Standalone play pages set
     * {@code __mcWorldAuto} so the old auto-start behaviour remains available.
     */
    private static void maybeStartWorld(Minecraft minecraft) {
        if (worldRequested || minecraft.level != null) {
            return;
        }
        if (!worldAuto() && !hostWorldRequested()) {
            return;
        }
        if (!(minecraft.gui.screen() instanceof TitleScreen) || !minecraft.isGameLoadFinished()) {
            titleSeenAt = 0L;
            return;
        }
        // Let the title screen render at least one frame before replacing it with the
        // world-loading screen. This used to count 20 frames, which is ~0.3 s at 60 fps
        // but ~2.5 minutes in a software-rendered browser at 0.13 fps. A short wall-clock
        // delay keeps the visual hand-off without coupling boot time to frame rate.
        long now = System.currentTimeMillis();
        if (titleSeenAt == 0L) {
            titleSeenAt = now;
            return;
        }
        if (now - titleSeenAt < 250L) {
            return;
        }
        // Minecraft.doWorldLoad() spins until gui.overlay() == null, and that
        // loop never runs the normal frame path that finishes the Java loading
        // overlay's fade-out. If world creation starts while the overlay is
        // still up, the client waits on it forever and the server never gets a
        // player. The host page already shows the single boot loader, so drop
        // the Java overlay before creating the world.
        try {
            if (minecraft.gui.overlay() != null) {
                minecraft.gui.setOverlay(null);
                BrowserRuntime.report("stage", "java-boot-overlay-cleared");
            }
        } catch (Throwable t) {
            BrowserRuntime.report("overlay-clear-error", t.getClass().getName());
        }
        worldRequested = true;
        BrowserRuntime.report("stage", "world-create-request-accepted");
        try {
            String name = settingName();
            if (name.isEmpty()) {
                name = "World";
            }
            String folder = settingFolder();
            if (folder.isEmpty()) {
                folder = name;
            }
            folder = sanitizeFolder(folder);

            boolean hardcore = "hardcore".equals(settingMode());
            LevelSettings settings = new LevelSettings(name, gameType(settingMode()),
                    new LevelSettings.DifficultySettings(hardcore ? Difficulty.HARD : difficulty(settingDifficulty()),
                            hardcore, hardcore),
                    settingCommands(), WorldDataConfiguration.DEFAULT);
            long seed;
            String seedText = settingSeed();
            if (seedText == null || seedText.isBlank()) {
                seed = WorldOptions.randomSeed();
            } else {
                java.util.OptionalLong parsed = WorldOptions.parseSeed(seedText);
                seed = parsed.isPresent() ? parsed.getAsLong() : WorldOptions.randomSeed();
            }
            WorldOptions options = new WorldOptions(seed, settingStructures(), settingBonus());
            ResourceKey<WorldPreset> preset = worldPreset(settingType());

            BrowserRuntime.report("world-folder", folder);
            BrowserRuntime.report("stage", "world-folder-check-enter");
            boolean existingWorld = minecraft.getLevelSource().levelExists(folder);
            BrowserRuntime.report("stage", existingWorld ? "existing-world-open-enter" : "fresh-world-create-enter");
            if (existingWorld) {
                minecraft.createWorldOpenFlows().openWorld(folder, () -> { });
            } else {
                freshWorld = true;
                minecraft.createWorldOpenFlows().createFreshLevel(folder, settings, options,
                        provider -> presetDimensions(provider, preset), null);
            }
            BrowserRuntime.report("stage", "world-start-call-return");
            // WorldOpenFlows.createFreshLevel swallows the exception from Minecraft.doWorldLoad
            // (it only logs "Failed to load datapacks") and restores the parent screen. A
            // successful call always leaves the LevelLoadingScreen up with the local connection
            // pending, so surface the failure instead of letting the smoke test time out.
            Object after = minecraft.gui.screen();
            if (minecraft.level == null && !(after instanceof LevelLoadingScreen)) {
                BrowserRuntime.report("stage", "world-start-returned-without-level");
                BrowserRuntime.report("world-start-screen",
                        after == null ? "null" : after.getClass().getName());
            }
        } catch (Throwable t) {
            worldRequested = false;
            BrowserRuntime.report("error", "world-start: " + BrowserRuntime.describe(t));
        }
    }

    private static void applyGameRules(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.player.connection == null) {
            return;
        }
        if (settingKeepInv()) {
            command(minecraft, "gamerule keepInventory true");
        }
        if (!settingDaylight()) {
            command(minecraft, "gamerule doDaylightCycle false");
        }
    }

    private static void command(Minecraft minecraft, String command) {
        try {
            minecraft.player.connection.sendUnattendedCommand(command, null);
        } catch (Throwable ignored) {
            // The host may have created the world without command permission.
        }
    }

    /**
     * Reports the preset of the world the integrated server actually created.
     * {@code ClientChunkCache} does not expose a generator, so read the
     * authoritative {@link ServerLevel}. This runs once per level and lets the
     * real-title-screen test distinguish "the Superflat button was clicked"
     * from "the server is generating a flat world".
     */
    private static boolean reportActualPreset(Minecraft minecraft) {
        try {
            net.minecraft.client.server.IntegratedServer server = minecraft.getSingleplayerServer();
            if (server == null || minecraft.level == null) {
                return false;
            }
            ServerLevel level = server.getLevel(minecraft.level.dimension());
            if (level == null) {
                return false;
            }
            ChunkGenerator generator = level.getChunkSource().getGenerator();
            String preset;
            if (generator instanceof FlatLevelSource) {
                preset = WorldPresets.FLAT.identifier().toString();
            } else if (generator instanceof net.minecraft.world.level.levelgen.DebugLevelSource) {
                preset = WorldPresets.DEBUG.identifier().toString();
            } else {
                preset = WorldPresets.NORMAL.identifier().toString();
            }
            BrowserRuntime.report("world-preset", preset);
            BrowserRuntime.report("world-generator", generator.getClass().getName());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static WorldDimensions presetDimensions(HolderLookup.Provider provider, ResourceKey<WorldPreset> preset) {
        BrowserRuntime.report("stage", "world-preset-dimensions-enter");
        try {
            BrowserRuntime.report("world-preset", preset.identifier().toString());
        } catch (Throwable ignored) {
        }
        if (preset == WorldPresets.NORMAL) {
            return WorldPresets.createNormalWorldDimensions(provider);
        }
        return provider.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(preset).value().createWorldDimensions();
    }

    private static ResourceKey<WorldPreset> worldPreset(String type) {
        if ("flat".equals(type)) {
            return WorldPresets.FLAT;
        }
        if ("large_biomes".equals(type) || "largeBiomes".equals(type)) {
            return WorldPresets.LARGE_BIOMES;
        }
        if ("amplified".equals(type)) {
            return WorldPresets.AMPLIFIED;
        }
        if ("single_biome".equals(type) || "singleBiome".equals(type)) {
            return WorldPresets.SINGLE_BIOME_SURFACE;
        }
        if ("debug".equals(type)) {
            return WorldPresets.DEBUG;
        }
        return WorldPresets.NORMAL;
    }

    private static GameType gameType(String mode) {
        if ("creative".equals(mode)) {
            return GameType.CREATIVE;
        }
        if ("adventure".equals(mode)) {
            return GameType.ADVENTURE;
        }
        if ("spectator".equals(mode)) {
            return GameType.SPECTATOR;
        }
        return GameType.SURVIVAL;
    }

    private static Difficulty difficulty(String name) {
        Difficulty value = name == null ? null : Difficulty.byName(name);
        return value == null ? Difficulty.NORMAL : value;
    }

    private static String sanitizeFolder(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 48; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "world" : sb.toString();
    }

    @JSBody(script = "return typeof globalThis.__mcWorldAuto !== 'undefined' && !!globalThis.__mcWorldAuto;")
    private static native boolean worldAuto();

    @JSBody(script = "return typeof globalThis.__mcWorldRequested !== 'undefined' && !!globalThis.__mcWorldRequested;")
    private static native boolean hostWorldRequested();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return String(w.name==null?'':w.name);")
    private static native String settingName();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return String(w.folder==null?'':w.folder);")
    private static native String settingFolder();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return String(w.seed==null?'':w.seed);")
    private static native String settingSeed();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return String(w.mode==null?'survival':w.mode);")
    private static native String settingMode();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return String(w.difficulty==null?'normal':w.difficulty);")
    private static native String settingDifficulty();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return String(w.type==null?'normal':w.type);")
    private static native String settingType();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return !!w.commands;")
    private static native boolean settingCommands();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return w.structures===undefined?true:!!w.structures;")
    private static native boolean settingStructures();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return !!w.bonus;")
    private static native boolean settingBonus();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return !!w.keepInv;")
    private static native boolean settingKeepInv();

    @JSBody(script = "var w=globalThis.__mcWorldRequested||{}; return w.daylight===undefined?true:!!w.daylight;")
    private static native boolean settingDaylight();

    /** Sends every non-air section of one loaded chunk. */
    public static void captureChunk(LevelChunk chunk) {
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        LevelChunkSection[] sections = chunk.getSections();
        Minecraft minecraft = Minecraft.getInstance();
        if (sections == null || minecraft == null || minecraft.level == null) {
            return;
        }
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null) {
                continue;
            }
            int cy = minecraft.level.getSectionYFromSectionIndex(index);
            captureSection(section, cx, cy, cz);
        }
    }

    /**
     * Captures one 16³ section. Public so a future worldgen hook (or a test) can
     * push immediately instead of waiting for the poller.
     */
    public static void captureSection(LevelChunkSection section, int cx, int cy, int cz) {
        if (section == null) {
            return;
        }
        if (!firstSectionLogged) {
            firstSectionLogged = true;
            BrowserRuntime.report("stage", "first-java-section-capture-enter");
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return;
        }
        long key = sectionKey(cx, cy, cz);

        // A section that became all air (the player mined it out, or a chunk was
        // replaced) must explicitly clear the Body's stale mesh.
        if (section.hasOnlyAir()) {
            SENT_LIGHT.remove(key);
            if (SENT.remove(key) != null) {
                emitSection(cx, cy, cz, Int32Array.create(4096));
            }
            return;
        }

        long hash = 0xcbf29ce484222325L;
        int previous = Integer.MIN_VALUE;
        int index = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    int id = Block.getId(state);
                    IDS.set(index++, id);
                    hash = (hash ^ (long) id) * 0x100000001b3L;
                    if (id != previous) {
                        registerPalette(id, state);
                        previous = id;
                    }
                }
            }
        }

        long lightHash = packLight(minecraft.level.getLightEngine(), cx, cy, cz);

        Long before = SENT.get(key);
        Long beforeLight = SENT_LIGHT.get(key);
        boolean idsSame = before != null && before.longValue() == hash;
        boolean lightSame = beforeLight != null && beforeLight.longValue() == lightHash;
        if (idsSame && lightSame) {
            return; // nothing changed since the last scan
        }
        SENT.put(key, hash);
        SENT_LIGHT.put(key, lightHash);
        emitSection(cx, cy, cz, IDS);
        emitLight(cx, cy, cz, LIGHT);
        if (!ready) {
            BrowserRuntime.report("stage", "first-java-section-emitted");
        }

        // Only now is there real geometry in the Body. The page keeps the
        // Mojang loading overlay up until this fires, so it never fades to an
        // empty demo world.
        if (!ready) {
            ready = true;
            emitReady();
        }
    }

    /**
     * Reads the two vanilla light layers Java already computed and packs them
     * exactly as the Body expects: {@code sky << 4 | block} per block.
     * Missing layers mean "not loaded yet": full sky, no block light.
     */
    private static long packLight(LevelLightEngine engine, int cx, int cy, int cz) {
        SectionPos pos = SectionPos.of(cx, cy, cz);
        LayerLightEventListener skyListener = engine.getLayerListener(LightLayer.SKY);
        LayerLightEventListener blockListener = engine.getLayerListener(LightLayer.BLOCK);
        DataLayer sky = skyListener == null ? null : skyListener.getDataLayerData(pos);
        DataLayer block = blockListener == null ? null : blockListener.getDataLayerData(pos);

        long hash = 0xcbf29ce484222325L;
        int index = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int s = sky == null ? 15 : sky.get(x, y, z);
                    int b = block == null ? 0 : block.get(x, y, z);
                    int packed = (s << 4) | b;
                    LIGHT.set(index++, (short) packed);
                    hash = (hash ^ (long) packed) * 0x100000001b3L;
                }
            }
        }
        return hash;
    }

    private static void registerPalette(int id, BlockState state) {
        if (id == 0 || PALETTE.containsKey(id)) {
            return;
        }
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        PALETTE.put(id, name);
        emitName(id, name);
    }

    private static long sectionKey(int cx, int cy, int cz) {
        return (((long) cx) & 0x3FFFFFFL) << 38
                | (((long) (cy + 64)) & 0x3FFL) << 28
                | (((long) cz) & 0xFFFFFFFL);
    }

    // --- JavaScript boundary -------------------------------------------------

    @JSBody(params = {"cx", "cy", "cz", "ids"}, script =
            "if (globalThis.mcBridge) globalThis.mcBridge.receiveJavaSection(cx, cy, cz, ids);")
    private static native void emitSection(int cx, int cy, int cz, Int32Array ids);

    @JSBody(params = {"cx", "cy", "cz", "packed"}, script =
            "if (globalThis.mcBridge) globalThis.mcBridge.receiveJavaLight(cx, cy, cz, packed);")
    private static native void emitLight(int cx, int cy, int cz, Uint8Array packed);

    @JSBody(params = {"fraction"}, script =
            "if (globalThis.mcBridge) globalThis.mcBridge.setTimeOfDay(fraction);")
    private static native void emitTime(double fraction);

    @JSBody(params = {}, script =
            "if (globalThis.mcBridge) globalThis.mcBridge.resetWorld();")
    private static native void emitReset();

    @JSBody(params = {"id", "name"}, script =
            "if (globalThis.mcBridge) globalThis.mcBridge.setBlockName(id, name);")
    private static native void emitName(int id, String name);

    @JSBody(params = {"x", "y", "z", "yaw", "pitch"}, script =
            "if (globalThis.mcBridge) globalThis.mcBridge.setPlayerPos(x, y, z, yaw, pitch);")
    private static native void sendPlayer(double x, double y, double z, float yaw, float pitch);

    @JSBody(params = {}, script =
            "if (globalThis.mcBridge) globalThis.mcBridge.javaReady();")
    private static native void emitReady();
}
