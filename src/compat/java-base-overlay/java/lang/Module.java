package java.lang;

import java.util.Collections;
import java.util.Set;

/**
 * Browser replacement for {@code java.lang.Module}.
 *
 * <p>TeaVM has no module layer. The original client probes
 * {@code Runtime.class.getModule().getLayer().findModule("jdk.jfr")} to decide
 * whether JFR profiling is available; returning a stable, non-null module lets
 * that probe complete without pulling in the JDK module system. The layer's
 * {@code findModule} always reports an empty result, so JFR stays disabled.</p>
 */
public final class Module {
    private final String name;
    private final ModuleLayer layer;

    Module(String name, ModuleLayer layer) {
        this.name = name;
        this.layer = layer;
    }

    public String getName() {
        return name;
    }

    public boolean isNamed() {
        return name != null;
    }

    public ModuleLayer getLayer() {
        return layer;
    }

    public Set<String> getPackages() {
        return Collections.emptySet();
    }

    public ClassLoader getClassLoader() {
        return null;
    }

    public boolean canRead(Module other) {
        return true;
    }

    public boolean isExported(String pkg) {
        return true;
    }

    public boolean isOpen(String pkg) {
        return true;
    }

    public java.io.InputStream getResourceAsStream(String name) {
        return null;
    }

    @Override
    public String toString() {
        return isNamed() ? "module " + name : "unnamed module";
    }
}
