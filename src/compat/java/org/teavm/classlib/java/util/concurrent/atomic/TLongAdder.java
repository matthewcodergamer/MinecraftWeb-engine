package org.teavm.classlib.java.util.concurrent.atomic;

/** java.util.concurrent.atomic.LongAdder: single-threaded JS needs no striping. */
public class TLongAdder extends Number implements java.io.Serializable {
    private long value;

    public void add(long x) {
        value += x;
    }

    public void increment() {
        value++;
    }

    public void decrement() {
        value--;
    }

    public long sum() {
        return value;
    }

    public void reset() {
        value = 0;
    }

    public long sumThenReset() {
        long v = value;
        value = 0;
        return v;
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }

    @Override
    public long longValue() {
        return value;
    }

    @Override
    public int intValue() {
        return (int) value;
    }

    @Override
    public float floatValue() {
        return value;
    }

    @Override
    public double doubleValue() {
        return value;
    }
}
