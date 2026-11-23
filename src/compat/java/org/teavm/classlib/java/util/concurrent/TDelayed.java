package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

/** java.util.concurrent.Delayed for the browser class library. */
public interface TDelayed extends Comparable<Delayed> {
    long getDelay(TimeUnit unit);
}
