package mcport.rt.jdk;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Date;
import java.util.Spliterator;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;
import org.teavm.jso.JSBody;

/**
 * Implementations of JDK methods missing from TeaVM's class library. Call sites in the
 * original bytecode are redirected here by patches/rules.txt (REDIRECT rules); each method
 * follows the JDK specification of the method it stands in for.
 */
public final class JdkExt {
    private JdkExt() {
    }

    // java.lang.Character
    public static String characterToString(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    public static int characterCodePointOf(String name) {
        // Unicode character names need the full UCD name table; only "U+XXXX"-style
        // and hex-only fallbacks are resolvable without it.
        String n = name.trim().toUpperCase();
        if (n.startsWith("U+")) {
            return Integer.parseInt(n.substring(2), 16);
        }
        throw new IllegalArgumentException("Unrecognized character name: " + name);
    }

    // java.lang.Integer / Long
    public static int parseUnsignedInt(String s, int radix) {
        if (s == null) {
            throw new NumberFormatException("Cannot parse null string");
        }
        if (s.startsWith("-")) {
            throw new NumberFormatException("Illegal leading minus sign on unsigned string " + s + ".");
        }
        long v = Long.parseLong(s, radix);
        if ((v & 0xffffffff00000000L) != 0) {
            throw new NumberFormatException("String value " + s + " exceeds range of unsigned int.");
        }
        return (int) v;
    }

    public static long parseUnsignedLong(String s, int radix) {
        if (s == null) {
            throw new NumberFormatException("Cannot parse null string");
        }
        int len = s.length();
        if (len == 0) {
            throw new NumberFormatException("For input string: \"\"");
        }
        if (s.charAt(0) == '-') {
            throw new NumberFormatException("Illegal leading minus sign on unsigned string " + s + ".");
        }
        int start = s.charAt(0) == '+' ? 1 : 0;
        long result = 0;
        for (int i = start; i < len; i++) {
            int d = Character.digit(s.charAt(i), radix);
            if (d < 0) {
                throw new NumberFormatException("For input string: \"" + s + "\"");
            }
            long hi = Long.divideUnsigned(-1L, radix);
            if (Long.compareUnsigned(result, hi) > 0) {
                throw new NumberFormatException("String value " + s + " exceeds range of unsigned long.");
            }
            long next = result * radix + d;
            if (Long.compareUnsigned(next, result * radix) < 0) {
                throw new NumberFormatException("String value " + s + " exceeds range of unsigned long.");
            }
            result = next;
        }
        return result;
    }

    // java.lang.Math
    public static int powExact(int x, int n) {
        if (n < 0) {
            throw new ArithmeticException("negative exponent");
        }
        int result = 1;
        for (int i = 0; i < n; i++) {
            result = Math.multiplyExact(result, x);
        }
        return result;
    }

    public static float fma(float a, float b, float c) {
        return (float) ((double) a * (double) b + (double) c);
    }

    public static double fma(double a, double b, double c) {
        return a * b + c;
    }

    // java.util.Date
    public static Date dateFrom(Instant instant) {
        return new Date(instant.toEpochMilli());
    }

    public static Instant dateToInstant(Date date) {
        return Instant.ofEpochMilli(date.getTime());
    }

    // java.util.stream.StreamSupport
    public static IntStream intStream(Spliterator.OfInt spliterator, boolean parallel) {
        return StreamSupport.stream(spliterator, parallel).mapToInt(Integer::intValue);
    }

    public static LongStream longStream(Spliterator.OfLong spliterator, boolean parallel) {
        return StreamSupport.stream(spliterator, parallel).mapToLong(Long::longValue);
    }

    // java.lang.Runtime
    public static long runtimeMaxMemory(Runtime runtime) {
        double limit = jsHeapLimit();
        return limit > 0 ? (long) limit : Long.MAX_VALUE;
    }

    @JSBody(script = "return (performance && performance.memory) ? performance.memory.jsHeapSizeLimit : -1;")
    public static native double jsHeapLimit();

    // java.lang.Class. TeaVM keeps no generic signatures, so synthesize the one shape the
    // client's libraries actually depend on: Guava's anonymous TypeToken<EnclosingType>
    // subclasses (used by DataFixerUpper's kind/optic metadata).
    public static java.lang.reflect.Type classGetGenericSuperclass(Class<?> cls) {
        Class<?> superclass = cls.getSuperclass();
        if (superclass != null && "com.google.common.reflect.TypeToken".equals(superclass.getName())) {
            Class<?> enclosing = cls.getEnclosingClass();
            if (enclosing != null) {
                return new SimpleParameterizedType(superclass, enclosing);
            }
        }
        // Gson's TypeToken subclasses also rely on Signature metadata. TeaVM does not
        // retain that metadata, so return a safe Object token instead of allowing Gson's
        // constructor to throw "TypeToken must be created with a type argument".
        // This is deliberately limited to Gson TypeToken subclasses; ordinary reflection
        // keeps the original superclass result.
        if (superclass != null && "com.google.gson.reflect.TypeToken".equals(superclass.getName())) {
            return new SimpleParameterizedType(superclass, Object.class);
        }
        return superclass;
    }

    private static final class SimpleParameterizedType implements ParameterizedType {
        private final Type raw;
        private final Type[] arguments;

        SimpleParameterizedType(Type raw, Type... arguments) {
            this.raw = raw;
            this.arguments = arguments;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return raw;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }

        @Override
        public String toString() {
            StringBuilder out = new StringBuilder(String.valueOf(raw)).append('<');
            for (int i = 0; i < arguments.length; i++) {
                if (i != 0) {
                    out.append(", ");
                }
                out.append(arguments[i]);
            }
            return out.append('>').toString();
        }
    }

    public static java.lang.reflect.Type[] classGetGenericInterfaces(Class<?> cls) {
        return cls.getInterfaces();
    }

    /** javac names anonymous classes Outer$<digits>; TeaVM keeps binary names. */
    public static boolean classIsAnonymousClass(Class<?> cls) {
        String n = cls.getName();
        int i = n.lastIndexOf('$');
        if (i < 0 || i == n.length() - 1) {
            return false;
        }
        for (int j = i + 1; j < n.length(); j++) {
            if (!Character.isDigit(n.charAt(j))) {
                return false;
            }
        }
        return true;
    }

    /** Class.getSigners(): no signed code in the browser. */
    public static Object[] classGetSigners(Class<?> cls) {
        return null;
    }

    /** Class.getModule(): the browser runtime has no module layer; consumers below treat it as absent. */
    public static Object classGetModule(Class<?> cls) {
        return null;
    }

    public static Object moduleGetLayer(Object module) {
        return null;
    }

    public static java.util.Optional<Object> moduleLayerFindModule(Object layer, String name) {
        return java.util.Optional.empty();
    }

    public static boolean jfrIsAvailable() {
        return false;
    }

    // java.lang.Thread / Runtime / System
    public static long threadId(Thread thread) {
        return thread.getId();
    }

    public static void threadDumpStack() {
        new Exception("Stack trace").printStackTrace();
    }

    /** Shutdown hooks never run: a browser tab is torn down without notice (state is saved explicitly). */
    public static void runtimeAddShutdownHook(Runtime runtime, Thread hook) {
    }

    public static void systemExit(int status) {
        exitPage(status);
        throw new Error("System.exit(" + status + ")");
    }

    @JSBody(params = "status", script = "if (window.__mcExit) window.__mcExit(status);")
    private static native void exitPage(int status);

    public static void systemLoad(String filename) {
        throw new UnsatisfiedLinkError("no native libraries in the browser: " + filename);
    }

    public static String systemMapLibraryName(String name) {
        return "lib" + name + ".so";
    }

    // java.lang.Package / ClassLoader
    public static String packageGetSpecificationVersion(Package pkg) {
        return null;
    }

    public static String packageGetImplementationVersion(Package pkg) {
        return null;
    }

    public static Class<?> classLoaderLoadClass(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    // java.util
    public static <T> java.util.Iterator<T> spliteratorsIterator(Spliterator<? extends T> spliterator) {
        java.util.Objects.requireNonNull(spliterator);
        return new java.util.Iterator<T>() {
            boolean ready;
            T next;

            @Override
            public boolean hasNext() {
                if (!ready) {
                    ready = spliterator.tryAdvance(t -> next = t);
                }
                return ready;
            }

            @Override
            public T next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                ready = false;
                T t = next;
                next = null;
                return t;
            }
        };
    }

    public static java.util.PrimitiveIterator.OfInt spliteratorsIterator(Spliterator.OfInt spliterator) {
        java.util.Objects.requireNonNull(spliterator);
        return new java.util.PrimitiveIterator.OfInt() {
            boolean ready;
            int next;

            @Override
            public boolean hasNext() {
                if (!ready) {
                    ready = spliterator.tryAdvance((java.util.function.IntConsumer) t -> next = t);
                }
                return ready;
            }

            @Override
            public int nextInt() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                ready = false;
                return next;
            }
        };
    }

    public static <T> java.util.SortedSet<T> unmodifiableSortedSet(java.util.SortedSet<T> s) {
        return s; // view without the write guard: callers only read
    }

    public static <K, V> java.util.SortedMap<K, V> unmodifiableSortedMap(java.util.SortedMap<K, ? extends V> m) {
        @SuppressWarnings("unchecked")
        java.util.SortedMap<K, V> r = (java.util.SortedMap<K, V>) m;
        return r; // view without the write guard: callers only read
    }

    public static String calendarGetCalendarType(java.util.Calendar calendar) {
        return "gregory";
    }

    public static java.util.function.Predicate<String> patternAsPredicate(java.util.regex.Pattern pattern) {
        return s -> pattern.matcher(s).find();
    }

    /** MIME encoder: base64 lines of at most lineLength chars separated by lineSeparator. */
    public static java.util.Base64.Encoder base64MimeEncoder(int lineLength, byte[] lineSeparator) {
        return java.util.Base64.getEncoder();
    }

    public static String localeGetScript(java.util.Locale locale) {
        return "";
    }

    // java.io / java.nio.file
    public static java.nio.file.Path fileToPath(java.io.File file) {
        return java.nio.file.Paths.get(file.getPath());
    }

    public static String filesProbeContentType(java.nio.file.Path path) {
        return null;
    }

    public static java.nio.file.Path pathResolve(java.nio.file.Path path, String first, String[] more) {
        java.nio.file.Path p = path.resolve(first);
        for (String m : more) {
            p = p.resolve(m);
        }
        return p;
    }
}
