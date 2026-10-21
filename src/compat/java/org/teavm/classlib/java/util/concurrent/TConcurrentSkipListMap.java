package org.teavm.classlib.java.util.concurrent;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentMap;

/** java.util.concurrent.ConcurrentSkipListMap as a sorted map (green threads make TreeMap safe). */
public class TConcurrentSkipListMap<K, V> extends TreeMap<K, V> implements ConcurrentMap<K, V> {
    private static final long serialVersionUID = 1L;

    public TConcurrentSkipListMap() {
    }

    public TConcurrentSkipListMap(Comparator<? super K> comparator) {
        super(comparator);
    }

    public TConcurrentSkipListMap(Map<? extends K, ? extends V> m) {
        super(m);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        V v = get(key);
        if (v == null) {
            put(key, value);
        }
        return v;
    }

    @Override
    public boolean remove(Object key, Object value) {
        if (containsKey(key) && java.util.Objects.equals(get(key), value)) {
            remove(key);
            return true;
        }
        return false;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        if (containsKey(key) && java.util.Objects.equals(get(key), oldValue)) {
            put(key, newValue);
            return true;
        }
        return false;
    }

    @Override
    public V replace(K key, V value) {
        return containsKey(key) ? put(key, value) : null;
    }
}
