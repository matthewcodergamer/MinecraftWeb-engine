package org.teavm.classlib.java.util.concurrent.locks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;

/** Reentrant readers/writer lock on a Java monitor. Writers may also take read locks. */
public class TReentrantReadWriteLock implements ReadWriteLock {
    private final Object monitor = new Object();
    private final Map<Thread, Integer> readers = new HashMap<>();
    private Thread writer;
    private int writeHolds;
    private final ReadLock readLock = new ReadLock();
    private final WriteLock writeLock = new WriteLock();

    public TReentrantReadWriteLock() {
    }

    public TReentrantReadWriteLock(boolean fair) {
    }

    @Override
    public ReadLock readLock() {
        return readLock;
    }

    @Override
    public WriteLock writeLock() {
        return writeLock;
    }

    public boolean isWriteLocked() {
        synchronized (monitor) {
            return writer != null;
        }
    }

    public boolean isWriteLockedByCurrentThread() {
        synchronized (monitor) {
            return writer == Thread.currentThread();
        }
    }

    public int getReadLockCount() {
        synchronized (monitor) {
            int n = 0;
            for (int c : readers.values()) {
                n += c;
            }
            return n;
        }
    }

    public class ReadLock implements TLock, Lock {
        @Override
        public void lock() {
            try {
                acquire(-1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            acquire(-1);
        }

        @Override
        public boolean tryLock() {
            synchronized (monitor) {
                Thread me = Thread.currentThread();
                if (writer != null && writer != me) {
                    return false;
                }
                readers.merge(me, 1, Integer::sum);
                return true;
            }
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return acquire(unit.toMillis(time));
        }

        private boolean acquire(long millis) throws InterruptedException {
            Thread me = Thread.currentThread();
            long deadline = millis < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + millis;
            synchronized (monitor) {
                while (writer != null && writer != me) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        return false;
                    }
                    monitor.wait(millis < 0 ? 0 : left);
                }
                readers.merge(me, 1, Integer::sum);
                return true;
            }
        }

        @Override
        public void unlock() {
            synchronized (monitor) {
                Thread me = Thread.currentThread();
                Integer c = readers.get(me);
                if (c == null) {
                    throw new IllegalMonitorStateException();
                }
                if (c == 1) {
                    readers.remove(me);
                } else {
                    readers.put(me, c - 1);
                }
                monitor.notifyAll();
            }
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    public class WriteLock implements TLock, Lock {
        @Override
        public void lock() {
            try {
                acquire(-1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            acquire(-1);
        }

        @Override
        public boolean tryLock() {
            try {
                return acquire(0);
            } catch (InterruptedException e) {
                return false;
            }
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            return acquire(unit.toMillis(time));
        }

        private boolean otherReaders(Thread me) {
            for (Thread t : readers.keySet()) {
                if (t != me) {
                    return true;
                }
            }
            return false;
        }

        private boolean acquire(long millis) throws InterruptedException {
            Thread me = Thread.currentThread();
            long deadline = millis < 0 ? Long.MAX_VALUE : System.currentTimeMillis() + millis;
            synchronized (monitor) {
                if (writer == me) {
                    writeHolds++;
                    return true;
                }
                while (writer != null || otherReaders(me)) {
                    long left = deadline - System.currentTimeMillis();
                    if (millis >= 0 && left <= 0) {
                        return false;
                    }
                    monitor.wait(millis < 0 ? 0 : left);
                }
                writer = me;
                writeHolds = 1;
                return true;
            }
        }

        @Override
        public void unlock() {
            synchronized (monitor) {
                if (writer != Thread.currentThread()) {
                    throw new IllegalMonitorStateException();
                }
                if (--writeHolds == 0) {
                    writer = null;
                    monitor.notifyAll();
                }
            }
        }

        @Override
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }
}
