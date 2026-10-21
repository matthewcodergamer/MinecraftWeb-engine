package org.teavm.classlib.java.lang;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * java.lang.ScopedValue (Java 25). Bindings are kept per thread in a stack-like map that is
 * restored when Carrier.run/call returns, matching the JDK's dynamic-scope semantics.
 */
public final class TScopedValue<T> {
    private static final ThreadLocal<Map<TScopedValue<?>, Object>> BINDINGS =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private static final Object UNBOUND = new Object();

    private TScopedValue() {
    }

    public static <T> TScopedValue<T> newInstance() {
        return new TScopedValue<>();
    }

    public static <T> Carrier where(TScopedValue<T> key, T value) {
        return new Carrier(null, key, value);
    }

    @SuppressWarnings("unchecked")
    public T get() {
        Map<TScopedValue<?>, Object> m = BINDINGS.get();
        if (!m.containsKey(this)) {
            throw new NoSuchElementException("ScopedValue not bound");
        }
        return (T) m.get(this);
    }

    public boolean isBound() {
        return BINDINGS.get().containsKey(this);
    }

    public T orElse(T other) {
        return isBound() ? get() : other;
    }

    public <X extends Throwable> T orElseThrow(Supplier<? extends X> exceptionSupplier) throws X {
        if (!isBound()) {
            throw exceptionSupplier.get();
        }
        return get();
    }

    @FunctionalInterface
    public interface CallableOp<T, X extends Throwable> {
        T call() throws X;
    }

    public static final class Carrier {
        private final Carrier prev;
        private final TScopedValue<?> key;
        private final Object value;

        Carrier(Carrier prev, TScopedValue<?> key, Object value) {
            this.prev = prev;
            this.key = key;
            this.value = value;
        }

        public <T> Carrier where(TScopedValue<T> key, T value) {
            return new Carrier(this, key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> T get(TScopedValue<T> key) {
            for (Carrier c = this; c != null; c = c.prev) {
                if (c.key == key) {
                    return (T) c.value;
                }
            }
            throw new NoSuchElementException();
        }

        public void run(Runnable op) {
            Map<TScopedValue<?>, Object> m = BINDINGS.get();
            Map<TScopedValue<?>, Object> saved = bind(m);
            try {
                op.run();
            } finally {
                restore(m, saved);
            }
        }

        public <R, X extends Throwable> R call(CallableOp<? extends R, X> op) throws X {
            Map<TScopedValue<?>, Object> m = BINDINGS.get();
            Map<TScopedValue<?>, Object> saved = bind(m);
            try {
                return op.call();
            } finally {
                restore(m, saved);
            }
        }

        private Map<TScopedValue<?>, Object> bind(Map<TScopedValue<?>, Object> m) {
            Map<TScopedValue<?>, Object> saved = new IdentityHashMap<>();
            for (Carrier c = this; c != null; c = c.prev) {
                if (!saved.containsKey(c.key)) {
                    saved.put(c.key, m.containsKey(c.key) ? m.get(c.key) : UNBOUND);
                }
            }
            java.util.ArrayList<Carrier> order = new java.util.ArrayList<>();
            for (Carrier c = this; c != null; c = c.prev) {
                order.add(0, c);
            }
            for (Carrier c : order) {
                m.put(c.key, c.value);
            }
            return saved;
        }

        private static void restore(Map<TScopedValue<?>, Object> m, Map<TScopedValue<?>, Object> saved) {
            for (Map.Entry<TScopedValue<?>, Object> e : saved.entrySet()) {
                if (e.getValue() == UNBOUND) {
                    m.remove(e.getKey());
                } else {
                    m.put(e.getKey(), e.getValue());
                }
            }
        }
    }
}
