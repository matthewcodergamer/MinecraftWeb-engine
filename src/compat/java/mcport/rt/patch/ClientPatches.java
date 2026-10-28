package mcport.rt.patch;

import java.util.List;
import java.util.Optional;
import org.teavm.jso.JSBody;

/**
 * Bodies for client methods that probe the host OS or load native libraries (patches/rules.txt).
 * In the browser the "natives" are provided by web/native/*.js and are always present.
 */
public final class ClientPatches {
    private ClientPatches() {
    }

    /** NativeLibrariesBootstrap.loadLibraries(): LWJGL libraries resolve to the JS natives table. */
    public static void loadNativeLibraries() {
    }

    public static boolean vulkanLoaderAvailable() {
        return false;
    }

    /** No-op used to replace browser-irrelevant static initializers (e.g. Vulkan feature tables). */
    public static void noop() {
    }

    /** NativeModuleLister (JNA, Windows-only module enumeration for crash reports). */
    public static List<Object> listNativeModules() {
        return List.of();
    }

    public static Optional<Object> nativeModuleVersion(String path) {
        return Optional.empty();
    }

    /** DebugEntrySystemSpecs.getCpuInfo() (OSHI). */
    public static String cpuInfo() {
        return cpuInfo0();
    }

    @JSBody(script = "return (navigator.hardwareConcurrency || '?') + 'x ' + (navigator.userAgentData && navigator.userAgentData.platform || navigator.platform || 'browser');")
    private static native String cpuInfo0();

    /** jtracy: the Tracy profiler is a native library. */
    public static void tracyLoad() {
        throw new UnsatisfiedLinkError("Tracy is unavailable in the browser");
    }

    /** TracyClient.beginZone(String, boolean) without the StackWalker caller lookup (Tracy is never loaded). */
    public static com.mojang.jtracy.Zone tracyBeginZone(String name, boolean callerInfo) {
        return com.mojang.jtracy.TracyClient.beginZone(name, "", "", 0);
    }
}
