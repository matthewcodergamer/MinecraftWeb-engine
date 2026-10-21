package org.teavm.classlib.java.util.concurrent.atomic;

/** java.util.concurrent.atomic.AtomicReferenceArray; TeaVM green threads only switch at blocking calls. */
@SuppressWarnings("unchecked")
public class TAtomicReferenceArray<E> implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final Object[] array;

    public TAtomicReferenceArray(int length) {
        array = new Object[length];
    }

    public TAtomicReferenceArray(E[] source) {
        array = java.util.Arrays.copyOf(source, source.length, Object[].class);
    }

    public final int length() {
        return array.length;
    }

    public final E get(int i) {
        return (E) array[i];
    }

    public final void set(int i, E v) {
        array[i] = v;
    }

    public final void lazySet(int i, E v) {
        array[i] = v;
    }

    public final E getPlain(int i) {
        return (E) array[i];
    }

    public final void setPlain(int i, E v) {
        array[i] = v;
    }

    public final E getOpaque(int i) {
        return (E) array[i];
    }

    public final void setOpaque(int i, E v) {
        array[i] = v;
    }

    public final E getAcquire(int i) {
        return (E) array[i];
    }

    public final void setRelease(int i, E v) {
        array[i] = v;
    }

    public final E getAndSet(int i, E v) {
        E old = (E) array[i];
        array[i] = v;
        return old;
    }

    public final boolean compareAndSet(int i, E expect, E update) {
        if (array[i] != expect) {
            return false;
        }
        array[i] = update;
        return true;
    }

    public final boolean weakCompareAndSet(int i, E expect, E update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetPlain(int i, E expect, E update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetVolatile(int i, E expect, E update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetAcquire(int i, E expect, E update) {
        return compareAndSet(i, expect, update);
    }

    public final boolean weakCompareAndSetRelease(int i, E expect, E update) {
        return compareAndSet(i, expect, update);
    }

    public final E compareAndExchange(int i, E expect, E update) {
        E old = (E) array[i];
        if (old == expect) {
            array[i] = update;
        }
        return old;
    }

    public final E getAndUpdate(int i, java.util.function.UnaryOperator<E> f) {
        E old = (E) array[i];
        array[i] = f.apply(old);
        return old;
    }

    public final E updateAndGet(int i, java.util.function.UnaryOperator<E> f) {
        E v = f.apply((E) array[i]);
        array[i] = v;
        return v;
    }

    public final E getAndAccumulate(int i, E x, java.util.function.BinaryOperator<E> f) {
        E old = (E) array[i];
        array[i] = f.apply(old, x);
        return old;
    }

    public final E accumulateAndGet(int i, E x, java.util.function.BinaryOperator<E> f) {
        E v = f.apply((E) array[i], x);
        array[i] = v;
        return v;
    }

    @Override
    public String toString() {
        return java.util.Arrays.toString(array);
    }
}
