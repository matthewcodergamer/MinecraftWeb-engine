package mcport.lwjgl;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.CallbackI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Bodies for LWJGL's platform layer (patches/rules.txt): no native libraries are loaded in the browser;
 * every "library" resolves its functions from the JS natives table (web/native/natives.js).
 */
public final class LwjglPatches {
    private LwjglPatches() {
    }

    // ---- org.lwjgl.system.Library ----

    public static void libraryInitialize() {
    }

    public static void loadSystem(String module, String name) {
    }

    public static void loadSystem(java.util.function.Consumer<String> load, java.util.function.Consumer<String> loadLibrary,
            Class<?> context, String module, String name) {
    }

    public static SharedLibrary loadNative(String module, String name) {
        return new BrowserSharedLibrary(name);
    }

    public static SharedLibrary loadNative(Class<?> context, String module, String name) {
        return new BrowserSharedLibrary(name);
    }

    public static SharedLibrary loadNative(Class<?> context, String module, String name, boolean bundledWithLWJGL) {
        return new BrowserSharedLibrary(name);
    }

    public static SharedLibrary loadNative(Class<?> context, String module,
            org.lwjgl.system.Configuration<String> name, String... defaultNames) {
        return new BrowserSharedLibrary(defaultNames.length > 0 ? defaultNames[0] : module);
    }

    public static SharedLibrary loadNative(Class<?> context, String module, org.lwjgl.system.Configuration<String> name,
            java.util.function.Supplier<SharedLibrary> fallback, String... defaultNames) {
        return new BrowserSharedLibrary(defaultNames.length > 0 ? defaultNames[0] : module);
    }

    // ---- org.lwjgl.system.MemoryUtil / MemoryAccessJNI ----

    public static int pageSize() {
        return 4096;
    }

    public static int cacheLineSize() {
        return 64;
    }

    public static int pointerSize() {
        return 8;
    }

    public static MemoryUtil.MemoryAllocator allocator() {
        return BrowserAllocator.INSTANCE;
    }

    public static long allocatorFunction() {
        return 0;
    }

    // ---- org.lwjgl.system.ThreadLocalUtil: no JNIEnv; capabilities are plain Java state ----

    public static long zeroJ() {
        return 0;
    }

    public static long zeroJI(int unused) {
        return 0;
    }

    // ---- org.lwjgl.system.Upcalls: callbacks become entries of the JS function table ----

    @JSFunctor
    public interface UpcallDispatcher extends JSObject {
        void call(int index, double ret, double args);
    }

    private static final List<Object> UPCALLS = new ArrayList<>();
    private static boolean dispatcherInstalled;

    @JSBody(params = "d", script = "__mcNative.setUpcallDispatcher(d);")
    private static native void installDispatcher(UpcallDispatcher d);

    @JSBody(params = {"index", "type"}, script = "return __mcNative.registerUpcall(index, type);")
    private static native int registerUpcall(int index, String type);

    @JSBody(params = "fn", script = "return __mcNative.upcallIndex(fn);")
    private static native int upcallIndex(int fn);

    @JSBody(params = "fn", script = "__mcNative.unregisterUpcall(fn);")
    private static native void unregisterUpcall(int fn);

    private static void dispatch(int index, double ret, double args) {
        Object target = UPCALLS.get(index);
        ((CallbackI) target).callback((long) ret, (long) args);
    }

    public static long upcallCreate(org.lwjgl.system.Callback.Descriptor descriptor, Object instance) {
        if (!dispatcherInstalled) {
            dispatcherInstalled = true;
            installDispatcher(LwjglPatches::dispatch);
        }
        int index = UPCALLS.size();
        UPCALLS.add(instance);
        return registerUpcall(index, instance.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    public static <T extends CallbackI> T upcallGet(long address) {
        int index = upcallIndex((int) address);
        return index < 0 ? null : (T) UPCALLS.get(index);
    }

    public static void upcallFree(long address) {
        int index = upcallIndex((int) address);
        if (index >= 0) {
            UPCALLS.set(index, null);
            unregisterUpcall((int) address);
        }
    }

    /** Callback descriptors carry a libffi CIF in the original; the browser bridge needs none. */
    public static org.lwjgl.system.libffi.FFICIF apiCreateCIF(org.lwjgl.system.libffi.FFIType rtype,
            org.lwjgl.system.libffi.FFIType... atypes) {
        return null;
    }

    public static org.lwjgl.system.libffi.FFICIF apiCreateCIF(int abi, org.lwjgl.system.libffi.FFIType rtype,
            org.lwjgl.system.libffi.FFIType... atypes) {
        return null;
    }

    public static org.lwjgl.system.libffi.FFICIF apiCreateCIFVar(int abi, int nfixedargs,
            org.lwjgl.system.libffi.FFIType rtype, org.lwjgl.system.libffi.FFIType... atypes) {
        return null;
    }

    /** java.lang.invoke.MethodHandles.lookup(): only stored in LWJGL callback descriptors, never used here. */
    public static java.lang.invoke.MethodHandles.Lookup methodHandlesLookup() {
        return null;
    }

    public static org.lwjgl.system.Platform platform() {
        return org.lwjgl.system.Platform.LINUX;
    }

    public static void noop() {
    }
}
