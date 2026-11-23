package org.teavm.classlib.java.util.concurrent;

import java.util.AbstractQueue;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** java.util.concurrent.LinkedBlockingQueue on a monitor (TeaVM green threads). */
public class TLinkedBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final LinkedList<E> items = new LinkedList<>();
    private final int capacity;

    public TLinkedBlockingQueue() {
        this(Integer.MAX_VALUE);
    }

    public TLinkedBlockingQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException();
        }
        this.capacity = capacity;
    }

    public TLinkedBlockingQueue(Collection<? extends E> c) {
        this(Integer.MAX_VALUE);
        addAll(c);
    }

    @Override
    public synchronized int size() {
        return items.size();
    }

    @Override
    public synchronized int remainingCapacity() {
        return capacity - items.size();
    }

    @Override
    public synchronized boolean offer(E e) {
        if (e == null) {
            throw new NullPointerException();
        }
        if (items.size() >= capacity) {
            return false;
        }
        items.addLast(e);
        notifyAll();
        return true;
    }

    private static long pollDelay(int emptyPolls) {
        return Math.min(1L << Math.min(emptyPolls, 3), 8L);
    }

    @Override
    public void put(E e) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException();
        }
        int emptyPolls = 0;
        while (true) {
            synchronized (this) {
                if (items.size() < capacity) {
                    items.addLast(e);
                    notifyAll();
                    return;
                }
            }
            Thread.sleep(pollDelay(emptyPolls++));
        }
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        if (e == null) {
            throw new NullPointerException();
        }
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        int emptyPolls = 0;
        while (true) {
            synchronized (this) {
                if (items.size() < capacity) {
                    items.addLast(e);
                    notifyAll();
                    return true;
                }
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return false;
            }
            Thread.sleep(Math.min(left, pollDelay(emptyPolls++)));
        }
    }

    @Override
    public E take() throws InterruptedException {
        int emptyPolls = 0;
        while (true) {
            synchronized (this) {
                if (!items.isEmpty()) {
                    E e = items.removeFirst();
                    notifyAll();
                    return e;
                }
            }
            Thread.sleep(pollDelay(emptyPolls++));
        }
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
        int emptyPolls = 0;
        while (true) {
            synchronized (this) {
                if (!items.isEmpty()) {
                    E e = items.removeFirst();
                    notifyAll();
                    return e;
                }
            }
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                return null;
            }
            Thread.sleep(Math.min(left, pollDelay(emptyPolls++)));
        }
    }

    @Override
    public synchronized E poll() {
        if (items.isEmpty()) {
            return null;
        }
        E e = items.removeFirst();
        notifyAll();
        return e;
    }

    @Override
    public synchronized E peek() {
        return items.peekFirst();
    }

    @Override
    public synchronized boolean remove(Object o) {
        boolean r = items.remove(o);
        if (r) {
            notifyAll();
        }
        return r;
    }

    @Override
    public synchronized boolean contains(Object o) {
        return items.contains(o);
    }

    @Override
    public synchronized void clear() {
        items.clear();
        notifyAll();
    }

    @Override
    public synchronized int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @Override
    public synchronized int drainTo(Collection<? super E> c, int maxElements) {
        int n = 0;
        while (n < maxElements && !items.isEmpty()) {
            c.add(items.removeFirst());
            n++;
        }
        if (n > 0) {
            notifyAll();
        }
        return n;
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
                TLinkedBlockingQueue.this.remove(last);
            }
        };
    }

    @Override
    public synchronized Object[] toArray() {
        return items.toArray();
    }

    @Override
    public synchronized <T> T[] toArray(T[] a) {
        return items.toArray(a);
    }
}
