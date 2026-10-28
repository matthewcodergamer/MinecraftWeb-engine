package joptsimple.internal;

/**
 * Accessor for the package-private {@link ReflectionException} constructor, for
 * mcport.rt.patch.LibraryPatches' direct {@code ValueConverter} implementations (they replace the
 * reflective converters that produced ReflectionException on the JVM and must keep its type).
 */
public final class ReflectionBridge {
    private ReflectionBridge() {
    }

    public static RuntimeException of(Throwable cause) {
        return new ReflectionException(cause);
    }
}
