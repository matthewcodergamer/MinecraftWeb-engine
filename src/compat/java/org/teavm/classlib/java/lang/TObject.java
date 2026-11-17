/*
 *  Copyright 2013 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.classlib.java.lang;

import java.util.ArrayDeque;
import java.util.Queue;
import mcport.rt.BrowserRuntime;
import mcport.rt.MonitorRegistry;
import org.teavm.backend.wasm.runtime.WasmGCSupport;
import org.teavm.classlib.PlatformDetector;
import org.teavm.dependency.PluggableDependency;
import org.teavm.interop.Address;
import org.teavm.interop.AsyncCallback;
import org.teavm.interop.DelegateTo;
import org.teavm.interop.NoSideEffects;
import org.teavm.interop.Rename;
import org.teavm.interop.Structure;
import org.teavm.interop.Superclass;
import org.teavm.interop.Unmanaged;
import org.teavm.jso.browser.TimerHandler;
import org.teavm.platform.Platform;
import org.teavm.platform.PlatformObject;
import org.teavm.platform.PlatformRunnable;
import org.teavm.runtime.Allocator;
import org.teavm.runtime.EventQueue;
import org.teavm.runtime.Fiber;
import org.teavm.runtime.RuntimeArray;
import org.teavm.runtime.RuntimeClass;
import org.teavm.runtime.RuntimeObject;
import org.teavm.runtime.reflect.ClassInfo;

@Superclass("")
public class TObject {
    Monitor monitor;

    static class Monitor {
        static final int MASK = 0x80000000;

        Queue<PlatformRunnable> enteringThreads;
        Queue<NotifyListener> notifyListeners;
        TThread owner;
        int count;
        int id;
        int traceId;
        MonitorRegistry.Entry entry;
        Object ownerFiber;
        int ownerFiberId = -1;

        Monitor() {
            this.owner = TThread.currentThread();
        }
    }

    private static int nextTraceId() {
        return MonitorRegistry.nextTraceId();
    }

    /** Snapshot of every owned or contended lock, for the boot watchdog. */
    public static String describeMonitors() {
        return MonitorRegistry.describe();
    }

    /**
     * Emits one line of monitor lifecycle diagnostics for a monitor that has actually
     * contended. Only the Wasm-GC browser target is instrumented; the rate limit lives
     * in the ordinary class so the class library statics cannot hide it.
     */
    private static void traceMonitor(Monitor monitor, String message) {
        if (monitor == null || !PlatformDetector.isWebAssemblyGC()) {
            return;
        }
        MonitorRegistry.trace(message);
    }

    private static void diag(String message) {
        MonitorRegistry.diag(message);
    }

    private static Object currentFiber() {
        try {
            return Fiber.current();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int currentFiberId() {
        try {
            return Fiber.currentId();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Single place where a monitor changes hands, so the Java thread, the owning
     * fiber and the runtime-visible registry can never disagree.
     */
    private static void setOwner(Monitor monitor, TThread thread) {
        monitor.owner = thread;
        monitor.ownerFiber = currentFiber();
        monitor.ownerFiberId = currentFiberId();
        MonitorRegistry.owner(monitor.entry, monitorThreadName(thread), monitor.ownerFiberId,
                monitor.count, waiterCount(monitor));
    }

    private static void clearOwner(Monitor monitor) {
        monitor.owner = null;
        monitor.ownerFiber = null;
        monitor.ownerFiberId = -1;
        MonitorRegistry.released(monitor.entry, waiterCount(monitor));
    }

    private static int waiterCount(Monitor monitor) {
        return monitor.enteringThreads != null ? monitor.enteringThreads.size() : 0;
    }

    private static boolean ownerIsDead(TThread owner) {
        if (owner == null) {
            return false;
        }
        try {
            return !owner.isAlive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String monitorThreadName(TThread thread) {
        try {
            return thread != null ? thread.getName() : "null";
        } catch (Throwable t) {
            return "?";
        }
    }

    interface NotifyListener extends PlatformRunnable, EventQueue.Event {
        boolean expired();
    }

    static void monitorEnterSync(TObject o) {
        if (Thread.currentThread() == null) {
            return;
        }
        if (o.monitor == null) {
            createMonitor(o);
        }
        if (o.monitor.owner == null) {
            setOwner(o.monitor, TThread.currentThread());
        } else if (o.monitor.owner != TThread.currentThread()) {
            throw new IllegalStateException("Can't enter monitor from another thread synchronously");
        }
        o.monitor.count++;
    }

    static void monitorExitSync(TObject o) {
        if (Thread.currentThread() == null) {
            return;
        }
        if (o.isEmptyMonitor() || o.monitor.owner != TThread.currentThread()) {
            throw new TIllegalMonitorStateException();
        }
        if (--o.monitor.count == 0) {
            clearOwner(o.monitor);
        }
        o.isEmptyMonitor();
    }

    static void monitorEnter(TObject o) {
        monitorEnter(o, 1);
    }

    static void monitorEnter(TObject o, int count) {
        if (o.monitor == null) {
           createMonitor(o);
        }
        if (o.monitor.owner == null) {
            o.monitor.count = 0;
            setOwner(o.monitor, TThread.currentThread());
            traceMonitor(o.monitor, "ACQUIRE m" + o.monitor.traceId + " obj=" + o.monitor.entry.objectClass
                    + " by=" + monitorThreadName(o.monitor.owner));
        }
        if (o.monitor.owner != TThread.currentThread()) {
            if (adoptAbandonedMonitor(o.monitor, count)) {
                return;
            }
            monitorEnterWait(o, count);
        } else {
            o.monitor.count += count;
        }
    }

    private static void createMonitor(TObject o) {
        if (PlatformDetector.isWebAssemblyGC()) {
            int hashCode = o.wasmGCIdentity();
            o.monitor = new Monitor();
            o.monitor.id = hashCode;
        } else if (PlatformDetector.isLowLevel()) {
            int hashCode = hashCodeLowLevel(o);
            o.monitor = new Monitor();
            o.monitor.id = hashCode;
        } else {
            o.monitor = new Monitor();
        }
        o.monitor.traceId = nextTraceId();
        String className = null;
        try {
            className = o.getClass().getName();
        } catch (Throwable ignored) {
        }
        o.monitor.entry = MonitorRegistry.register(o.monitor.traceId, className);
        if (o.monitor.owner != null) {
            setOwner(o.monitor, o.monitor.owner);
        }
    }

    /**
     * Recovers a monitor whose recorded owner can never release it:
     *  - the same cooperative fiber already owns it but the recorded Java thread
     *    instance changed identity across a suspension point (self-deadlock), or
     *  - the owning thread has terminated without running its monitorExit.
     * In both cases the lock is effectively free for the calling fiber.
     */
    private static boolean adoptAbandonedMonitor(Monitor monitor, int count) {
        Object fiber = currentFiber();
        boolean sameFiber = fiber != null && monitor.ownerFiber == fiber;
        boolean deadOwner = ownerIsDead(monitor.owner);
        if (!sameFiber && !deadOwner) {
            return false;
        }
        traceMonitor(monitor, (sameFiber ? "STALE-OWNER m" : "DEAD-OWNER m") + monitor.traceId
                + " obj=" + monitor.entry.objectClass + " recorded=" + monitorThreadName(monitor.owner)
                + " count=" + monitor.count + " by=" + monitorThreadName(TThread.currentThread()));
        monitor.count += count;
        setOwner(monitor, TThread.currentThread());
        return true;
    }

    static void monitorEnterWait(TObject o, int count) {
        TObjectAsync.monitorEnterWait(o, count);
    }

    static void monitorEnterWait(TObject o, int count, AsyncCallback<Void> callback) {
        TThread thread = TThread.currentThread();
        if (o.monitor == null) {
            createMonitor(o);
            TThread.setCurrentThread(thread);
            o.monitor.count += count;
            callback.complete(null);
            return;
        } else if (o.monitor.owner == null) {
            TThread.setCurrentThread(thread);
            o.monitor.count = count;
            setOwner(o.monitor, thread);
            traceMonitor(o.monitor, "ACQUIRE m" + o.monitor.traceId + " obj=" + o.monitor.entry.objectClass
                    + " by=" + monitorThreadName(thread));
            callback.complete(null);
            return;
        }

        Monitor monitor = o.monitor;
        diag("enter-wait m" + monitor.traceId + " obj=" + monitor.entry.objectClass
                + " owner=" + monitorThreadName(monitor.owner) + " count=" + monitor.count
                + " by=" + monitorThreadName(thread));
        if (adoptAbandonedMonitor(monitor, count)) {
            TThread.setCurrentThread(thread);
            callback.complete(null);
            return;
        }
        traceMonitor(monitor, "WAIT m" + monitor.traceId + " obj=" + monitor.entry.objectClass
                + " owner=" + monitorThreadName(monitor.owner)
                + " count=" + monitor.count + " by=" + monitorThreadName(thread)
                + " stack=" + BrowserRuntime.captureStack());
        if (monitor.enteringThreads == null) {
            monitor.enteringThreads = new ArrayDeque<>();
        }
        MonitorRegistry.owner(monitor.entry, monitorThreadName(monitor.owner), monitor.ownerFiberId,
                monitor.count, waiterCount(monitor) + 1);
        monitor.enteringThreads.add(() -> {
            TThread.setCurrentThread(thread);
            o.monitor.count += count;
            setOwner(o.monitor, thread);
            traceMonitor(monitor, "GRANT m" + monitor.traceId + " to=" + monitorThreadName(thread));
            callback.complete(null);
        });
    }

    static void monitorExit(TObject o) {
        monitorExit(o, 1);
    }

    static void monitorExit(TObject o, int count) {
        if (o.isEmptyMonitor() || o.monitor.owner != TThread.currentThread()) {
            throw new TIllegalMonitorStateException();
        }

        Monitor monitor = o.monitor;
        monitor.count -= count;
        if (monitor.count > 0) {
            MonitorRegistry.owner(monitor.entry, monitorThreadName(monitor.owner), monitor.ownerFiberId,
                    monitor.count, waiterCount(monitor));
            return;
        }

        clearOwner(monitor);
        if (monitor.enteringThreads != null && !monitor.enteringThreads.isEmpty()) {
            traceMonitor(monitor, "RELEASE m" + monitor.traceId + " obj=" + monitor.entry.objectClass + " by="
                    + monitorThreadName(TThread.currentThread()) + " waiters=" + monitor.enteringThreads.size());
            if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
                EventQueue.offer(() -> waitForOtherThreads(o));
            } else {
                Platform.postpone(() -> waitForOtherThreads(o));
            }
        } else {
            o.isEmptyMonitor();
        }
    }

    private static void waitForOtherThreads(TObject o) {
        if (o.isEmptyMonitor() || o.monitor.owner != null) {
            return;
        }
        Monitor monitor = o.monitor;
        if (monitor.enteringThreads != null && !monitor.enteringThreads.isEmpty()) {
            var enteringThreads = monitor.enteringThreads;
            PlatformRunnable r = enteringThreads.remove();
            // Keep the remaining contenders queued. Clearing this queue after
            // granting only one waiter silently strands every other fiber that
            // was waiting for the same monitor; the next owner release is
            // responsible for granting the next waiter.
            if (enteringThreads.isEmpty()) {
                monitor.enteringThreads = null;
            }
            r.run();
        }
    }

    final boolean isEmptyMonitor() {
        Monitor monitor = this.monitor;
        if (monitor == null) {
            return true;
        }
        if (monitor.owner == null
                && (monitor.enteringThreads == null || monitor.enteringThreads.isEmpty())
                && (monitor.notifyListeners == null || monitor.notifyListeners.isEmpty())) {
            deleteMonitor();
            return true;
        } else {
            return false;
        }
    }

    private void deleteMonitor() {
        if (PlatformDetector.isLowLevel()) {
            int id = monitor.id;
            setHashCodeLowLevel(this, id);
        } else if (PlatformDetector.isWebAssemblyGC()) {
            setWasmGCIdentity(monitor.id);
        } else {
            monitor = null;
        }
    }

    static boolean holdsLock(TObject o) {
        return o.monitor != null && o.monitor.owner == TThread.currentThread();
    }

    @Rename("fakeInit")
    public TObject() {
    }

    @Rename("<init>")
    private void init() {
    }

    @Rename("getClass")
    public final TClass<?> getClass0() {
        return (TClass<?>) (Object) getObjectClassInfo(this).classObject();
    }

    public static native ClassInfo getObjectClassInfo(Object obj);

    @Override
    public int hashCode() {
        return identity();
    }

    @Override
    public boolean equals(Object obj) {
        return equals0((TObject) obj);
    }

    @Rename("equals")
    public boolean equals0(TObject other) {
        return this == other;
    }

    @Override
    public String toString() {
        return getClass().getName() + "@" + TInteger.toHexString(identity());
    }

    private String obfuscatedToString() {
        return "<java_object>@" + Integer.toHexString(identity());
    }

    final int identity() {
        if (PlatformDetector.isWebAssemblyGC()) {
            var identity = wasmGCIdentity();
            if (identity < 0) {
                var monitor = this.monitor;
                if (monitor != null) {
                    if (monitor.id < 0) {
                        monitor.id = WasmGCSupport.nextObjectId() & 0x7fffffff;
                    }
                    return monitor.id;
                } else {
                    identity = WasmGCSupport.nextObjectId() & 0x7fffffff;
                    setWasmGCIdentity(identity);
                }
            }
            return identity;
        } else if (PlatformDetector.isLowLevel()) {
            Monitor monitor = this.monitor;
            if (monitor == null) {
                int hashCode = hashCodeLowLevel(this);
                if (hashCode == 0) {
                    hashCode = identityLowLevel();
                    setHashCodeLowLevel(this, hashCode);
                }
                return hashCode;
            } else {
                int hashCode = monitor.id;
                if (hashCode == 0) {
                    hashCode = identityLowLevel();
                    monitor.id = hashCode;
                }
                return hashCode;
            }
        }
        PlatformObject platformThis = Platform.getPlatformObject(this);
        if (platformThis.getId() == 0) {
            platformThis.setId(Platform.nextObjectId());
        }
        return Platform.getPlatformObject(this).getId();
    }

    private native int wasmGCIdentity();

    private native void setWasmGCIdentity(int identity);

    @DelegateTo("hashCodeLowLevelImpl")
    @NoSideEffects
    @Unmanaged
    private static native int hashCodeLowLevel(TObject obj);

    @Unmanaged
    private static int hashCodeLowLevelImpl(RuntimeObject obj) {
        return obj.hashCode;
    }

    @DelegateTo("setHashCodeLowLevelImpl")
    @NoSideEffects
    @Unmanaged
    private static native void setHashCodeLowLevel(TObject obj, int value);

    @Unmanaged
    private static void setHashCodeLowLevelImpl(RuntimeObject obj, int value) {
        obj.hashCode = value;
    }

    @Unmanaged
    private static int identityLowLevel() {
        int result = RuntimeObject.nextId++;
        if (result == 0) {
            result = RuntimeObject.nextId++;
            if (result == Monitor.MASK) {
                result = 1;
            }
        }
        return result;
    }

    @DelegateTo("identityOrMonitorLowLevel")
    @NoSideEffects
    private native int identityOrMonitor();

    private static int identityOrMonitorLowLevel(RuntimeObject object) {
        return object.hashCode;
    }

    @DelegateTo("setIdentityLowLevel")
    @NoSideEffects
    native void setIdentity(int id);

    private static void setIdentityLowLevel(RuntimeObject object, int id) {
        object.hashCode = id;
    }

    @Override
    @DelegateTo("cloneLowLevel")
    @PluggableDependency(ObjectDependencyPlugin.class)
    protected Object clone() throws TCloneNotSupportedException {
        if (PlatformDetector.isWebAssemblyGC()) {
            return cloneObject();
        }
        var cls = ((TClass<?>) (Object) getClass()).getClassInfo();
        if (!(this instanceof TCloneable) && cls.itemType() == null) {
            throw new TCloneNotSupportedException();
        }
        Object result = Platform.clone(this);
        Platform.getPlatformObject(result).setId(Platform.nextObjectId());
        return result;
    }

    private native TObject cloneObject();

    @SuppressWarnings("unused")
    private static RuntimeObject cloneLowLevel(RuntimeObject self) {
        RuntimeClass cls = RuntimeClass.getClass(self);
        int skip = Structure.sizeOf(RuntimeObject.class);
        int size;
        RuntimeObject copy;
        if (cls.itemType == null) {
            copy = Allocator.allocate(cls).toStructure();
            size = cls.size;
        } else {
            RuntimeArray array = (RuntimeArray) self;
            copy = Allocator.allocateArray(cls, array.size).toStructure();
            int itemSize = RuntimeClass.isPrimitive(cls) ? cls.itemType.size : Address.sizeOf();
            Address headerSize = Address.align(Address.fromInt(Structure.sizeOf(RuntimeArray.class)), itemSize);
            size = itemSize * array.size + headerSize.toInt();
        }
        if (size > skip) {
            Address.moveMemoryBlock(self.toAddress().add(skip), copy.toAddress().add(skip), size - skip);
        }
        return copy;
    }

    @Rename("notify")
    public final void notify0() {
        if (!holdsLock(this)) {
            throw new TIllegalMonitorStateException();
        }
        var listeners = monitor.notifyListeners;
        if (listeners == null) {
            return;
        }
        while (!listeners.isEmpty()) {
            NotifyListener listener = listeners.remove();
            if (!listener.expired()) {
                if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
                    EventQueue.offer(listener);
                } else {
                    Platform.postpone(listener);
                }
                break;
            }
        }
        if (listeners.isEmpty()) {
            monitor.notifyListeners = null;
        }
    }

    @Rename("notifyAll")
    public final void notifyAll0() {
        if (!holdsLock(this)) {
            throw new TIllegalMonitorStateException();
        }
        var listeners = monitor.notifyListeners;
        if (listeners == null) {
            return;
        }
        while (!listeners.isEmpty()) {
            NotifyListener listener = listeners.remove();
            if (!listener.expired()) {
                if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
                    EventQueue.offer(listener);
                } else {
                    Platform.postpone(listener);
                }
            }
        }
        monitor.notifyListeners = null;
    }

    @Rename("wait")
    public final void wait0(long timeout) throws TInterruptedException {
        try {
            wait(timeout, 0);
        } catch (InterruptedException ex) {
            throw new TInterruptedException();
        }
    }

    @Rename("wait")
    private void wait0(long timeout, int nanos) throws TInterruptedException {
        if (!holdsLock(this)) {
            throw new TIllegalMonitorStateException();
        }
        waitImpl(timeout, nanos);
    }

    private void waitImpl(long timeout, int nanos) throws TInterruptedException {
        TObjectAsync.waitImpl(this, timeout, nanos);
    }

    final void waitImpl(long timeout, int nanos, AsyncCallback<Void> callback) {
        Monitor monitor = this.monitor;
        var listener = new NotifyListenerImpl(this, callback, monitor.count);
        if (monitor.notifyListeners == null) {
            monitor.notifyListeners = new ArrayDeque<>();
        }
        monitor.notifyListeners.add(listener);
        TThread.currentThread().interruptHandler = listener;
        if (timeout > 0 || nanos > 0) {
            int timeoutToSchedule = timeout >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) timeout;
            listener.timerId = PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()
                    ? EventQueue.offer(listener, timeoutToSchedule + System.currentTimeMillis())
                    : Platform.schedule(listener, timeoutToSchedule);
        }
        monitorExit(this, monitor.count);
    }

    static class NotifyListenerImpl implements NotifyListener, TimerHandler, PlatformRunnable,
            TThreadInterruptHandler {
        final TObject obj;
        final AsyncCallback<Void> callback;
        final TThread currentThread = TThread.currentThread();
        int timerId = -1;
        boolean expired;
        boolean performed;
        int lockCount;

        NotifyListenerImpl(TObject obj, AsyncCallback<Void> callback, int lockCount) {
            this.obj = obj;
            this.callback = callback;
            this.lockCount = lockCount;
        }

        @Override
        public boolean expired() {
            boolean result = expired;
            expired = true;
            return result;
        }

        @Override
        public void onTimer() {
            if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
                EventQueue.offer(() -> {
                    if (!expired()) {
                        run();
                    }
                });
            } else {
                Platform.postpone(() -> {
                    if (!expired()) {
                        run();
                    }
                });
            }
        }

        @Override
        public void run() {
            if (performed) {
                return;
            }
            performed = true;
            if (timerId >= 0) {
                if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
                    EventQueue.kill(timerId);
                } else {
                    Platform.killSchedule(timerId);
                }
                timerId = -1;
            }
            TThread.setCurrentThread(currentThread);
            monitorEnterWait(obj, lockCount, callback);
        }

        @Override
        public void interrupted() {
            if (performed) {
                return;
            }
            performed = true;
            if (timerId >= 0) {
                if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
                    EventQueue.kill(timerId);
                } else {
                    Platform.killSchedule(timerId);
                }
                timerId = -1;
            }
            if (PlatformDetector.isLowLevel() || PlatformDetector.isWebAssemblyGC()) {
                EventQueue.offer(() -> callback.error(new TInterruptedException()));
            } else {
                Platform.postpone(() -> callback.error(new TInterruptedException()));
            }
        }
    }

    @Rename("wait")
    public final void wait0() throws TInterruptedException {
        try {
            wait(0L);
        } catch (InterruptedException ex) {
            throw new TInterruptedException();
        }
    }

    @Override
    protected void finalize() throws TThrowable {
    }
}
