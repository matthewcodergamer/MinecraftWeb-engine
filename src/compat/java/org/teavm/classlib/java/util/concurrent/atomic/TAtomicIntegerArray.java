package org.teavm.classlib.java.util.concurrent.atomic;

/** java.util.concurrent.atomic.AtomicIntegerArray; TeaVM green threads only switch at blocking calls. */
@SuppressWarnings("unchecked")
public class TAtomicIntegerArray implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final int[] array;

    public TAtomicIntegerArray(int length) {
        array = new int[length];
    }

    public TAtomicIntegerArray(int[] source) {
        array = java.util.Arrays.copyOf(source, source.length);
    }

    public final int length() {
        return array.length;
    }

    public final int get(int i) {
        return array[i];
    }

    public final void set(int i, int v) {
        array[i] = v;
    }

    public final void lazySet(int i, int v) {
        array[i] = v;
    }

    public final int getPlain(int i) {
        return array[i];
    }

    public final void setPlain(int i, int v) {
        array[i] = v;
    }

    public final int getOpaque(int i) {
        return array[i];
    }

    public final void setOpaque(int i, int v) {
        array[i] = v;
    }

    public final int getAcquire(int i) {
        return array[i];
    }

    public final void setRelease(int i, int v) {
        array[i] = v;
    }

    public final int getAndSet(int i, int v) {
        int old = array[i];
        array[i] = v;
        return old;
    }

    public final boolean compareAndSet(int i, int expect, int update) {
        if (array[i] != expect) {
            return false;
        }
        array[i] = update;
        return true;
    }

    public final boolean weakCompareAndSet(int i, int expect, int update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetPlain(int i, int expect, int update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetVolatile(int i, int expect, int update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetAcquire(int i, int expect, int update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetRelease(int i, int expect, int update) {
        return compareAndSet(i, expect, update);
    }

    public final int compareAndExchange(int i, int expect, int update) {
        int old = array[i];
        if (old == expect) {
            array[i] = update;
        }
        return old;
    }

    public final int getAndUpdate(int i, java.util.function.IntUnaryOperator f) {
        int old = array[i];
        array[i] = f.applyAsInt(old);
        return old;
    }

    public final int updateAndGet(int i, java.util.function.IntUnaryOperator f) {
        int v = f.applyAsInt(array[i]);
        array[i] = v;
        return v;
    }

    public final int getAndAccumulate(int i, int x, java.util.function.IntBinaryOperator f) {
        int old = array[i];
        array[i] = f.applyAsInt(old, x);
        return old;
    }

    public final int accumulateAndGet(int i, int x, java.util.function.IntBinaryOperator f) {
        int v = f.applyAsInt(array[i], x);
        array[i] = v;
        return v;
    }

    public final int getAndIncrement(int i) {
        return array[i]++;
    }

    public final int getAndDecrement(int i) {
        return array[i]--;
    }

    public final int getAndAdd(int i, int delta) {
        int old = array[i];
        array[i] += delta;
        return old;
    }

    public final int incrementAndGet(int i) {
        return ++array[i];
    }

    public final int decrementAndGet(int i) {
        return --array[i];
    }

    public final int addAndGet(int i, int delta) {
        return array[i] += delta;
    }

    @Override
    public String toString() {
        return java.util.Arrays.toString(array);
    }
}
