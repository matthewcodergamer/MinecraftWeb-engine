package mcport.rt;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * The client jar's non-class files (version.json, assets/, data/), loaded by the host page into
 * window.__mcResources before Java starts. Class/ClassLoader resource lookups in the original
 * bytecode are redirected here (patches/rules.txt).
 */
public final class ClasspathResources {
    private ClasspathResources() {
    }

    @JSBody(params = "path", script = "var r = window.__mcResources; if (!r) return null;"
            + "var e = r.index[path]; if (!e) return null;"
            + "return new Int8Array(r.data, e[0], e[1]);")
    private static native Int8Array lookup(String path);

    @JSBody(params = "path", script = "var r = window.__mcResources; return !!(r && r.index[path]);")
    public static native boolean exists(String path);

    public static byte[] bytes(String path) {
        Int8Array view = lookup(normalize(path));
        if (view == null) {
            return null;
        }
        // TeaVM's zero-copy @JSByRef bridge is not supported by WebAssembly GC.
        // copyToJavaArray uses the optional-by-ref bridge and performs the supported copy.
        return view.copyToJavaArray();
    }


    private static String normalize(String path) {
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        return path;
    }

    /** Class.getResourceAsStream semantics: relative names resolve against the class's package. */
    private static String resolve(Class<?> cls, String name) {
        if (name.startsWith("/")) {
            return name.substring(1);
        }
        String cn = cls.getName();
        int dot = cn.lastIndexOf('.');
        return dot < 0 ? name : cn.substring(0, dot).replace('.', '/') + "/" + name;
    }

    public static InputStream open(String path) {
        byte[] b = bytes(path);
        return b == null ? null : new ByteArrayInputStream(b);
    }

    // --- redirect targets -------------------------------------------------------------------

    public static InputStream classGetResourceAsStream(Class<?> cls, String name) {
        return open(resolve(cls, name));
    }

    public static InputStream loaderGetResourceAsStream(ClassLoader loader, String name) {
        return open(name);
    }

    public static InputStream systemGetResourceAsStream(String name) {
        return open(name);
    }

    public static URL classGetResource(Class<?> cls, String name) {
        return url(resolve(cls, name));
    }

    public static URL loaderGetResource(ClassLoader loader, String name) {
        return url(normalize(name));
    }

    public static Enumeration<URL> loaderGetResources(ClassLoader loader, String name) {
        URL u = url(normalize(name));
        return u == null ? Collections.emptyEnumeration() : Collections.enumeration(Collections.singletonList(u));
    }

    private static URL url(String path) {
        if (!exists(path)) {
            return null;
        }
        try {
            return new URL("jar:file:/minecraft-client.jar!/" + path);
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }
}
