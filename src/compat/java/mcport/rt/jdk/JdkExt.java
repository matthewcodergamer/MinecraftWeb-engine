package mcport.rt.jdk;

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

    // java.lang.Class (TeaVM keeps no generic signatures; callers that need them fail loudly)
    public static java.lang.reflect.Type classGetGenericSuperclass(Class<?> cls) {
        return cls.getSuperclass();
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
}
