package mcport.lwjgl;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;
import org.teavm.jso.JSBody;

/** A "native library" whose function addresses index the JS natives table (web/native/natives.js). */
public final class BrowserSharedLibrary implements SharedLibrary {
    private final String name;
    private final long handle;

    public BrowserSharedLibrary(String name) {
        this.name = name;
        this.handle = openLibrary(name);
    }

    @JSBody(params = "name", script = "return __mcNative.openLibrary(name);")
    private static native int openLibrary(String name);

    @JSBody(params = {"lib", "fn"}, script = "return __mcNative.lookup(lib, fn);")
    private static native int lookup(String lib, String fn);

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getPath() {
        return "browser:" + name;
    }

    @Override
    public long getFunctionAddress(CharSequence functionName) {
        return lookup(name, functionName.toString());
    }

    @Override
    public long getFunctionAddress(ByteBuffer functionName) {
        return lookup(name, MemoryUtil.memASCII(functionName));
    }

    @Override
    public long address() {
        return handle;
    }

    @Override
    public void free() {
    }
}
