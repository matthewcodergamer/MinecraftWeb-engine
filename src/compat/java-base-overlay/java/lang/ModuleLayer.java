package java.lang;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/**
 * Browser replacement for {@code java.lang.ModuleLayer}. The port has no
 * module graph; this exists only so module-availability probes can complete.
 */
public final class ModuleLayer {
    private static final ModuleLayer BOOT = new ModuleLayer();
    private final Module module = new Module("minecraft", this);

    private ModuleLayer() {
    }

    public static ModuleLayer boot() {
        return BOOT;
    }

    public Optional<Module> findModule(String name) {
        return Optional.empty();
    }

    public Set<Module> modules() {
        return Collections.singleton(module);
    }

    public ModuleLayer parents() {
        return this;
    }

    @Override
    public String toString() {
        return "ModuleLayer";
    }
}
