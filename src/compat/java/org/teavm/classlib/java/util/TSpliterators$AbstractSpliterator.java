package org.teavm.classlib.java.util;

import java.util.Spliterator;
import java.util.function.Consumer;

/** java.util.Spliterators.AbstractSpliterator (JDK semantics: batch-splitting by array copy). */
public abstract class TSpliterators$AbstractSpliterator<T> implements Spliterator<T> {
    private final int characteristics;
    private long est;
    private int batch;

    protected TSpliterators$AbstractSpliterator(long est, int additionalCharacteristics) {
        this.est = est;
        this.characteristics = ((additionalCharacteristics & Spliterator.SIZED) != 0)
                ? additionalCharacteristics | Spliterator.SUBSIZED : additionalCharacteristics;
    }

    @Override
    public Spliterator<T> trySplit() {
        Object[] holder = new Object[1];
        long s = est;
        if (s > 1 && tryAdvance(x -> holder[0] = x)) {
            int n = batch + 1024;
            if (n > s) {
                n = (int) s;
            }
            if (n > (1 << 25)) {
                n = 1 << 25;
            }
            Object[] a = new Object[n];
            int j = 0;
            do {
                a[j] = holder[0];
            } while (++j < n && tryAdvance(x -> holder[0] = x));
            batch = j;
            if (est != Long.MAX_VALUE) {
                est -= j;
            }
            return java.util.Spliterators.spliterator(a, 0, j, characteristics());
        }
        return null;
    }

    @Override
    public long estimateSize() {
        return est;
    }

    @Override
    public int characteristics() {
        return characteristics;
    }
}
