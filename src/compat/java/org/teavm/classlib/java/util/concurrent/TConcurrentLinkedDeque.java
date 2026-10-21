package org.teavm.classlib.java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;

/** java.util.concurrent.ConcurrentLinkedDeque (weakly consistent snapshot iterators). */
public class TConcurrentLinkedDeque<E> extends java.util.AbstractCollection<E> implements Deque<E>,
        java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final LinkedList<E> items = new LinkedList<>();

    public TConcurrentLinkedDeque() {
    }

    public TConcurrentLinkedDeque(Collection<? extends E> c) {
        addAll(c);
    }

    private static <E> E nn(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        return e;
    }

    @Override
    public synchronized void addFirst(E e) {
        items.addFirst(nn(e));
    }

    @Override
    public synchronized void addLast(E e) {
        items.addLast(nn(e));
    }

    @Override
    public synchronized boolean offerFirst(E e) {
        items.addFirst(nn(e));
        return true;
    }

    @Override
    public synchronized boolean offerLast(E e) {
        items.addLast(nn(e));
        return true;
    }

    @Override
    public synchronized E removeFirst() {
        return items.removeFirst();
    }

    @Override
    public synchronized E removeLast() {
        return items.removeLast();
    }

    @Override
    public synchronized E pollFirst() {
        return items.pollFirst();
    }

    @Override
    public synchronized E pollLast() {
        return items.pollLast();
    }

    @Override
    public synchronized E getFirst() {
        return items.getFirst();
    }

    @Override
    public synchronized E getLast() {
        return items.getLast();
    }

    @Override
    public synchronized E peekFirst() {
        return items.peekFirst();
    }

    @Override
    public synchronized E peekLast() {
        return items.peekLast();
    }

    @Override
    public synchronized boolean removeFirstOccurrence(Object o) {
        return items.removeFirstOccurrence(o);
    }

    @Override
    public synchronized boolean removeLastOccurrence(Object o) {
        return items.removeLastOccurrence(o);
    }

    @Override
    public boolean add(E e) {
        addLast(e);
        return true;
    }

    @Override
    public boolean offer(E e) {
        return offerLast(e);
    }

    @Override
    public E remove() {
        return removeFirst();
    }

    @Override
    public E poll() {
        return pollFirst();
    }

    @Override
    public E element() {
        return getFirst();
    }

    @Override
    public E peek() {
        return peekFirst();
    }

    @Override
    public void push(E e) {
        addFirst(e);
    }

    @Override
    public E pop() {
        return removeFirst();
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
    public synchronized int size() {
        return items.size();
    }

    @Override
    public synchronized void clear() {
        items.clear();
    }

    @Override
    public synchronized Iterator<E> iterator() {
        return new ArrayList<>(items).iterator();
    }

    @Override
    public synchronized Iterator<E> descendingIterator() {
        ArrayList<E> copy = new ArrayList<>(items);
        java.util.Collections.reverse(copy);
        return copy.iterator();
    }

    @Override
    public Deque<E> reversed() {
        throw new UnsupportedOperationException("reversed view");
    }
}
