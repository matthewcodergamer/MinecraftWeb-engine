package org.teavm.classlib.java.util.concurrent;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Browser-compatible java.util.concurrent.CopyOnWriteArraySet.
 *
 * TeaVM maps this T-prefixed class to the JDK class. The browser runtime uses
 * cooperative/single-threaded execution, so a LinkedHashSet is sufficient for
 * mutation while iterators retain the JDK copy-on-write snapshot semantics.
 */
public class TCopyOnWriteArraySet<E> extends AbstractSet<E> implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    private final Set<E> items = new LinkedHashSet<>();

    public TCopyOnWriteArraySet() {
    }

    public TCopyOnWriteArraySet(Collection<? extends E> collection) {
        addAll(collection);
    }

    @Override
    public synchronized int size() {
        return items.size();
    }

    @Override
    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    @Override
    public synchronized boolean contains(Object value) {
        return items.contains(value);
    }

    @Override
    public synchronized boolean add(E value) {
        return items.add(value);
    }

    @Override
    public synchronized boolean remove(Object value) {
        return items.remove(value);
    }

    @Override
    public synchronized void clear() {
        items.clear();
    }

    @Override
    public synchronized boolean addAll(Collection<? extends E> collection) {
        boolean changed = false;
        for (E value : collection) {
            changed |= items.add(value);
        }
        return changed;
    }

    /** Adds only elements not already present and returns the number inserted. */
    public synchronized int addAllAbsent(Collection<? extends E> collection) {
        int added = 0;
        for (E value : collection) {
            if (items.add(value)) {
                added++;
            }
        }
        return added;
    }

    @Override
    public synchronized boolean removeAll(Collection<?> collection) {
        return items.removeAll(collection);
    }

    @Override
    public synchronized boolean retainAll(Collection<?> collection) {
        return items.retainAll(collection);
    }

    @Override
    public synchronized Iterator<E> iterator() {
        // JDK CopyOnWriteArraySet iterators are immutable snapshots.
        return Collections.unmodifiableList(new ArrayList<>(items)).iterator();
    }

    @Override
    public synchronized Object[] toArray() {
        return items.toArray();
    }

    @Override
    public synchronized <T> T[] toArray(T[] array) {
        return items.toArray(array);
    }
}
