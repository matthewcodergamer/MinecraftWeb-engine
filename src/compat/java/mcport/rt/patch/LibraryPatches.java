package mcport.rt.patch;

import com.google.gson.internal.UnsafeAllocator;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;

/** Targets of BODY rules for third-party libraries bundled with Minecraft. */
public final class LibraryPatches {
    private LibraryPatches() {
    }

    /**
     * Gson UnsafeAllocator.create(): with no sun.misc.Unsafe/ObjectStreamClass, Gson's own
     * last-resort allocator is one that refuses to allocate. Same message shape as Gson's.
     */
    public static UnsafeAllocator gsonUnsafeAllocator() {
        return new UnsafeAllocator() {
            @Override
            public <T> T newInstance(Class<T> c) {
                throw new UnsupportedOperationException("Cannot allocate " + c
                        + ". Usage of JDK sun.misc.Unsafe is not available in the browser; register an"
                        + " InstanceCreator or a TypeAdapter for this type, or add a no-args constructor.");
            }
        };
    }

    /** Netty InternalLoggerFactory.newDefaultFactory(name): SLF4J is the only backend present. */
    public static InternalLoggerFactory nettyDefaultFactory(String name) {
        return Slf4JLoggerFactory.INSTANCE;
    }
}
