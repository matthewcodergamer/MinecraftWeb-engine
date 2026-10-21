package org.teavm.classlib.java.util.concurrent.atomic;

/** java.util.concurrent.atomic.AtomicLongArray; TeaVM green threads only switch at blocking calls. */
@SuppressWarnings("unchecked")
public class TAtomicLongArray implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final long[] array;

    public TAtomicLongArray(int length) {
        array = new long[length];
    }

    public TAtomicLongArray(long[] source) {
        array = java.util.Arrays.copyOf(source, source.length);
    }

    public final int length() {
        return array.length;
    }

    public final long get(int i) {
        return array[i];
    }

    public final void set(int i, long v) {
        array[i] = v;
    }

    public final void lazySet(int i, long v) {
        array[i] = v;
    }

    public final long getPlain(int i) {
        return array[i];
    }

    public final void setPlain(int i, long v) {
        array[i] = v;
    }

    public final long getOpaque(int i) {
        return array[i];
    }

    public final void setOpaque(int i, long v) {
        array[i] = v;
    }

    public final long getAcquire(int i) {
        return array[i];
    }

    public final void setRelease(int i, long v) {
        array[i] = v;
    }

    public final long getAndSet(int i, long v) {
        long old = array[i];
        array[i] = v;
        return old;
    }

    public final boolean compareAndSet(int i, long expect, long update) {
        if (array[i] != expect) {
            return false;
        }
        array[i] = update;
        return true;
    }

    public final boolean weakCompareAndSet(int i, long expect, long update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetPlain(int i, long expect, long update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetVolatile(int i, long expect, long update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetAcquire(int i, long expect, long update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetRelease(int i, long expect, long update) {
        return compareAndSet(i, expect, update);
    }

    public final long compareAndExchange(int i, long expect, long update) {
        long old = array[i];
        if (old == expect) {
            array[i] = update;
        }
        return old;
    }

    public final long getAndUpdate(int i, java.util.function.LongUnaryOperator f) {
        long old = array[i];
        array[i] = f.applyAsLong(old);
        return old;
    }

    public final long updateAndGet(int i, java.util.function.LongUnaryOperator f) {
        long v = f.applyAsLong(array[i]);
        array[i] = v;
        return v;
    }

    public final long getAndAccumulate(int i, long x, java.util.function.LongBinaryOperator f) {
        long old = array[i];
        array[i] = f.applyAsLong(old, x);
        return old;
    }

    public final long accumulateAndGet(int i, long x, java.util.function.LongBinaryOperator f) {
        long v = f.applyAsLong(array[i], x);
        array[i] = v;
        return v;
    }

    public final long getAndIncrement(int i) {
        return array[i]++;
    }

    public final long getAndDecrement(int i) {
        return array[i]--;
    }

    public final long getAndAdd(int i, long delta) {
        long old = array[i];
        array[i] += delta;
        return old;
    }

    public final long incrementAndGet(int i) {
        return ++array[i];
    }

    public final long decrementAndGet(int i) {
        return --array[i];
    }

    public final long addAndGet(int i, long delta) {
        return array[i] += delta;
    }

    @Override
    public String toString() {
        return java.util.Arrays.toString(array);
    }
}
