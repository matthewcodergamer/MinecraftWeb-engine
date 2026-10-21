package org.teavm.classlib.java.util.concurrent;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;

/** java.util.concurrent.ConcurrentLinkedQueue (weakly consistent snapshot iterator). */
public class TConcurrentLinkedQueue<E> extends AbstractQueue<E> implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final LinkedList<E> items = new LinkedList<>();

    public TConcurrentLinkedQueue() {
    }

    public TConcurrentLinkedQueue(Collection<? extends E> c) {
        addAll(c);
    }

    @Override
    public synchronized boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        items.addLast(e);
        return true;
    }

    @Override
    public synchronized E poll() {
        return items.pollFirst();
    }

    @Override
    public synchronized E peek() {
        return items.peekFirst();
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
    public synchronized boolean remove(Object o) {
        return items.remove(o);
    }

    @Override
    public synchronized boolean contains(Object o) {
        return items.contains(o);
    }

    @Override
    public synchronized void clear() {
        items.clear();
    }

    @Override
    public synchronized Iterator<E> iterator() {
        Iterator<E> it = new ArrayList<>(items).iterator();
        return new Iterator<E>() {
            private E last;

            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public E next() {
                last = it.next();
                return last;
            }

            @Override
            public void remove() {
                TConcurrentLinkedQueue.this.remove(last);
            }
        };
    }
}
