package org.teavm.classlib.java.util.concurrent;

import java.util.concurrent.Delayed;
import java.util.concurrent.Future;

/** java.util.concurrent.ScheduledFuture for TeaVM. */
public interface TScheduledFuture<V> extends Future<V>, Delayed {
}
