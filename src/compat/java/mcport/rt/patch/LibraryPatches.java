package mcport.rt.patch;

import com.google.gson.internal.UnsafeAllocator;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Slf4JLoggerFactory;
import joptsimple.ValueConverter;
import joptsimple.internal.ReflectionBridge;

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

    /**
     * joptsimple.internal.Reflection.findConverter(Class): the library resolves a {@link ValueConverter}
     * for a CLI option's declared type reflectively, by looking up a static {@code valueOf(String)} or a
     * one-String-argument constructor. The browser port keeps no reflective member metadata for
     * java.io.File (only code-reachable members are preserved), so the lookup fails. Converters for the
     * types the client passes to ofType() are supplied directly; arbitrary other types take the JVM
     * semantics as far as reflective lookup goes.
     */
    @SuppressWarnings("rawtypes")
    public static ValueConverter joptFindConverter(Class<?> type) {
        if (type.isPrimitive()) {
            type = wrapperOf(type);
        }
        if (type == String.class) {
            return IdConverter.INSTANCE; // both valueOf lookup and copy constructor keep the string
        }
        if (type == Boolean.class || type == Byte.class || type == Short.class) {
            return new WrappingConverter(type);
        }
        if (type == Integer.class) {
            return WrappingConverter.INTEGER;
        }
        if (type == Long.class || type == Float.class || type == Double.class) {
            return new WrappingConverter(type);
        }
        if (type == java.io.File.class) {
            return FileConverter.INSTANCE;
        }
        // Types beyond the client's usage: try joptsimple's own reflective order (valueOf(String) static
        // with matching return type, then a (String) constructor) using reflection metadata, and fail
        // with the same message as the original when nothing matches.
        try {
            java.lang.reflect.Method valueOf = type.getMethod("valueOf", String.class);
            if (java.lang.reflect.Modifier.isStatic(valueOf.getModifiers())
                    && valueOf.getReturnType() == type) {
                return new ValueOfConverter(valueOf, type);
            }
        } catch (NoSuchMethodException e) {
            // fall through
        }
        try {
            return new ConstructorConverter(type.getConstructor(String.class));
        } catch (NoSuchMethodException e) {
            // fall through
        }
        throw new IllegalArgumentException("class " + type + " is not a value type");
    }

    private static Class<?> wrapperOf(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == char.class) {
            return Character.class; // the JVM path then finds no Character value type either
        }
        if (type == void.class) {
            throw new IllegalArgumentException("class " + type + " is not a value type");
        }
        return type; // not primitive after all
    }

    /** joptsimple.internal.Reflection.reflectionException, which the reflective converters rely on. */
    private static RuntimeException reflectionException(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return ReflectionBridge.of(e);
        }
        if (e instanceof java.lang.reflect.InvocationTargetException) {
            return ReflectionBridge.of(e.getCause());
        }
        if (e instanceof RuntimeException) {
            return (RuntimeException) e;
        }
        return ReflectionBridge.of(e);
    }

    /** joptsimple's value pattern concept has no browser equivalent; the JVM converters all return null. */
    private static final class IdConverter implements ValueConverter<Object> {
        static final IdConverter INSTANCE = new IdConverter();

        @Override
        public Object convert(String value) {
            return value;
        }

        @Override
        public Class<?> valueType() {
            return String.class;
        }

        @Override
        public String valuePattern() {
            return null;
        }
    }

    /** Boxed numeric/boolean types: static valueOf(String), exactly what joptsimple resolves reflectively. */
    private static final class WrappingConverter implements ValueConverter<Object> {
        static final WrappingConverter INTEGER = new WrappingConverter(Integer.class);
        private final Class<?> type;

        WrappingConverter(Class<?> type) {
            this.type = type;
        }

        @Override
        public Object convert(String value) {
            try {
                if (type == Boolean.class) {
                    return Boolean.valueOf(value);
                }
                if (type == Byte.class) {
                    return Byte.valueOf(value);
                }
                if (type == Short.class) {
                    return Short.valueOf(value);
                }
                if (type == Integer.class) {
                    return Integer.valueOf(value);
                }
                if (type == Long.class) {
                    return Long.valueOf(value);
                }
                if (type == Float.class) {
                    return Float.valueOf(value);
                }
                return Double.valueOf(value);
            } catch (NumberFormatException e) {
                // Reflectively invoked valueOf surfaces parse failures as ReflectionException(NFE).
                throw ReflectionBridge.of(e);
            }
        }

        @Override
        public Class<?> valueType() {
            return type;
        }

        @Override
        public String valuePattern() {
            return null;
        }
    }

    /** java.io.File(String): the path form, wrapped by the archive-aware File implementation. */
    private static final class FileConverter implements ValueConverter<Object> {
        static final FileConverter INSTANCE = new FileConverter();

        @Override
        public Object convert(String value) {
            return new java.io.File(value);
        }

        @Override
        public Class<?> valueType() {
            return java.io.File.class;
        }

        @Override
        public String valuePattern() {
            return null;
        }
    }

    /** Reflection-backed converters for types the client does not use (kept for faithful behaviour). */
    private static final class ValueOfConverter implements ValueConverter<Object> {
        private final java.lang.reflect.Method valueOf;
        private final Class<?> type;

        ValueOfConverter(java.lang.reflect.Method valueOf, Class<?> type) {
            this.valueOf = valueOf;
            this.type = type;
        }

        @Override
        public Object convert(String value) {
            try {
                return type.cast(valueOf.invoke(null, value));
            } catch (Exception e) {
                throw reflectionException(e);
            }
        }

        @Override
        public Class<?> valueType() {
            return type;
        }

        @Override
        public String valuePattern() {
            return null;
        }
    }

    private static final class ConstructorConverter implements ValueConverter<Object> {
        private final java.lang.reflect.Constructor<?> constructor;

        ConstructorConverter(java.lang.reflect.Constructor<?> constructor) {
            this.constructor = constructor;
        }

        @Override
        public Object convert(String value) {
            try {
                return constructor.newInstance(value);
            } catch (Exception e) {
                throw reflectionException(e);
            }
        }

        @Override
        public Class<?> valueType() {
            return constructor.getDeclaringClass();
        }

        @Override
        public String valuePattern() {
            return null;
        }
    }
}
