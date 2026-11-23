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

        WaitQueue<PlatformRunnable> enteringThreads;
        WaitQueue<NotifyListener> notifyListeners;
        TThread owner;
        int count;
        int id;
        int traceId;
        // Incremented by notify/notifyAll. Object.wait polls this instead of
        // parking on a monitor callback: the callback path can strand a
        // Wasm-GC fiber when the timer and notify race, which wedged the
        // render thread inside LockSupport.parkNanos during world load.
        int waitGeneration;
        int waiting;
        MonitorRegistry.Entry entry;
        Object ownerFiber;
        int ownerFiberId = -1;

        // Staged enter context. Set only while this fiber is inside the enqueue
        // block, so the watchdog can finish an enter that never reached the queue.
        TThread pendingThread;
        Object pendingFiber;
        int pendingFiberId = -1;
        int pendingCount;
        AsyncCallback<Void> pendingCallback;

        Monitor() {
            this.owner = TThread.currentThread();
        }
    }

    /**
     * Small, synchronous FIFO for monitor waiters. It deliberately avoids
     * java.util collections so enqueue/dequeue never enter collection code
     * that may be transformed into an async monitor operation by TeaVM.
     */
    private static final class WaitQueue<E> {
        private Object[] elements = new Object[4];
        private int head;
        private int size;

        int size() { return size; }
        boolean isEmpty() { return size == 0; }

        void add(E element) {
            if (size == elements.length) {
                Object[] expanded = new Object[elements.length << 1];
                for (int i = 0; i < size; ++i) {
                    expanded[i] = elements[(head + i) % elements.length];
                }
                elements = expanded;
                head = 0;
            }
            elements[(head + size) % elements.length] = element;
            ++size;
        }

        @SuppressWarnings("unchecked")
        E remove() {
            if (size == 0) throw new IllegalStateException("Queue is empty");
            E result = (E) elements[head];
            elements[head] = null;
            head = (head + 1) % elements.length;
            --size;
            return result;
        }

        /** Removes one specific element, if present. */
        void remove(E element) {
            for (int i = 0; i < size; ++i) {
                int index = (head + i) % elements.length;
                if (elements[index] == element) {
                    for (int j = i; j < size - 1; ++j) {
                        elements[(head + j) % elements.length]
                                = elements[(head + j + 1) % elements.length];
                    }
                    elements[(head + size - 1) % elements.length] = null;
                    --size;
                    return;
                }
            }
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
        if (monitor == null) {
            return;
        }
        try {
            MonitorRegistry.platform(PlatformDetector.isWebAssemblyGC());
            MonitorRegistry.trace(message);
        } catch (Throwable ignored) {
            // Diagnostics must never abort a monitor handoff.
        }
    }

    private static void diag(String message) {
        try {
            MonitorRegistry.diag(message);
        } catch (Throwable ignored) {
            // Diagnostics must never abort a monitor handoff.
        }
    }

    /**
     * Class name of a monitor, or {@code "?"} when its registry entry has not been
     * created. Building diagnostics must never dereference a missing entry: doing so
     * aborts the contended enter path before the waiter is queued and strands every
     * later contender.
     */
    private static String safeObjectClass(Monitor monitor) {
        try {
            return monitor != null && monitor.entry != null && monitor.entry.objectClass != null
                    ? monitor.entry.objectClass
                    : "?";
        } catch (Throwable ignored) {
            return "?";
        }
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
        setOwner(monitor, thread, currentFiber(), currentFiberId());
    }

    private static void setOwner(Monitor monitor, TThread thread, Object fiber, int fiberId) {
        monitor.owner = thread;
        monitor.ownerFiber = fiber;
        monitor.ownerFiberId = fiberId;
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
        MonitorRegistry.syncEnterCall();
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
        MonitorRegistry.enterCall();
        if (o.monitor == null) {
           createMonitor(o);
        }
        if (o.monitor.owner == null) {
            o.monitor.count = count;
            setOwner(o.monitor, TThread.currentThread());
            traceMonitor(o.monitor, "ACQUIRE m" + o.monitor.traceId + " obj=" + safeObjectClass(o.monitor)
                    + " by=" + monitorThreadName(o.monitor.owner));
        } else if (o.monitor.owner != TThread.currentThread()) {
            MonitorRegistry.step("enter-contended");
            monitorEnterWait(o, count);
        } else {
            o.monitor.count += count;
            MonitorRegistry.owner(o.monitor.entry, monitorThreadName(o.monitor.owner),
                    o.monitor.ownerFiberId, o.monitor.count, waiterCount(o.monitor));
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

    static void monitorEnterWait(TObject o, int count) {
        TObjectAsync.monitorEnterWait(o, count);
    }

    static void monitorEnterWait(TObject o, int count, AsyncCallback<Void> callback) {
        monitorEnterWait(o, count, callback, currentFiber(), currentFiberId());
    }

    /**
     * Reacquires a monitor for the fiber that originally called Object.wait.
     * EventQueue dispatches NotifyListenerImpl.run outside that fiber's execution
     * context, so asking Fiber.current() there can return a different fiber (or
     * no fiber). The listener captures the suspended fiber before parking and
     * passes that identity through this overload.
     */
    private static void monitorEnterWait(TObject o, int count, AsyncCallback<Void> callback,
            Object waiterFiber, int waiterFiberId) {
        MonitorRegistry.asyncEnterCall();
        MonitorRegistry.step("enter-wait");
        TThread thread = TThread.currentThread();
        MonitorRegistry.step("captured");
        Monitor monitor = o.monitor;
        try {
            String className = null;
            try {
                className = o.getClass().getName();
            } catch (Throwable ignored) {
            }
            MonitorRegistry.enterSite(className, BrowserRuntime.captureStack());
        } catch (Throwable ignored) {
        }
        if (monitor == null) {
            createMonitor(o);
            TThread.setCurrentThread(thread);
            o.monitor.count += count;
            MonitorRegistry.step("new-monitor-grant");
            callback.complete(null);
            MonitorRegistry.step("new-monitor-complete");
            return;
        } else if (monitor.owner == null) {
            TThread.setCurrentThread(thread);
            monitor.count = count;
            setOwner(monitor, thread);
            traceMonitor(monitor, "ACQUIRE m" + monitor.traceId + " obj=" + safeObjectClass(monitor)
                    + " by=" + monitorThreadName(thread));
            MonitorRegistry.immediateAcquire();
            MonitorRegistry.step("free-monitor-grant");
            callback.complete(null);
            MonitorRegistry.step("free-monitor-complete");
            return;
        }

        // Re-entering a monitor owned by the same cooperative fiber. After a
        // suspension the Java Thread instance can differ, so owner != currentThread()
        // while no other fiber can ever release the lock; waiting would deadlock.
        boolean sameFiber = (waiterFiber != null && monitor.ownerFiber == waiterFiber)
                || (waiterFiberId >= 0 && monitor.ownerFiberId == waiterFiberId);
        if (sameFiber) {
            monitor.count += count;
            setOwner(monitor, thread, waiterFiber, waiterFiberId);
            MonitorRegistry.selfReentry();
            MonitorRegistry.step("self-reentry-grant");
            callback.complete(null);
            MonitorRegistry.step("self-reentry-complete");
            return;
        }

        // Queue the waiter before any bookkeeping or string building. Whatever the
        // diagnostics below do, a release now has a fiber to grant.
        monitor.pendingThread = thread;
        monitor.pendingFiber = waiterFiber;
        monitor.pendingFiberId = waiterFiberId;
        monitor.pendingCount = count;
        monitor.pendingCallback = callback;
        MonitorRegistry.watchEnter(monitor, o, count, thread, waiterFiber, waiterFiberId, callback);
        MonitorRegistry.step("before-enqueue");
        try {
            if (monitor.enteringThreads == null) {
                MonitorRegistry.step("queue-init");
                monitor.enteringThreads = new WaitQueue<>();
            }
            MonitorRegistry.step("queue-ready");
            PlatformRunnable grant = () -> {
                monitor.pendingCallback = null;
                TThread.setCurrentThread(thread);
                o.monitor.count += count;
                setOwner(o.monitor, thread, waiterFiber, waiterFiberId);
                MonitorRegistry.grantCall();
                try {
                    MonitorRegistry.granted(monitor.traceId, waiterCount(monitor));
                } catch (Throwable ignored) {
                }
                traceMonitor(monitor, "GRANT m" + monitor.traceId + " to=" + monitorThreadName(thread));
                callback.complete(null);
            };
            MonitorRegistry.step("grant-ready");
            monitor.enteringThreads.add(grant);
            MonitorRegistry.step("queued");
            MonitorRegistry.clearWatchedEnter(monitor);
        } catch (Throwable t) {
            MonitorRegistry.step("enqueue-error");
            MonitorRegistry.clearWatchedEnter(monitor);
            try {
                MonitorRegistry.callbackError("enqueue " + errorText(t));
            } catch (Throwable ignored) {
            }
            // Never leave a suspended fiber without a wakeup. If queueing failed,
            // resume it with the error so the failure is visible instead of a hang.
            callback.error(t);
            return;
        }

        try {
            MonitorRegistry.asyncWaitCall();
            MonitorRegistry.enqueued(monitor.traceId, monitor.ownerFiberId, waiterFiberId,
                    monitor.enteringThreads.size());
            MonitorRegistry.step("enqueue-recorded");
            if (monitor.entry == null && MonitorRegistry.allowRepair()) {
                String className = null;
                try {
                    className = o.getClass().getName();
                } catch (Throwable ignored) {
                }
                monitor.entry = MonitorRegistry.register(monitor.traceId, className);
                MonitorRegistry.entryRepaired();
            }
            int waiters = waiterCount(monitor);
            MonitorRegistry.contended(monitor.traceId, monitor.entry == null, monitor.id,
                    monitor.ownerFiberId, monitor.count, waiters);
            MonitorRegistry.owner(monitor.entry, monitorThreadName(monitor.owner),
                    monitor.ownerFiberId, monitor.count, waiters);
            diag("enter-wait m" + monitor.traceId + " obj=" + safeObjectClass(monitor)
                    + " owner=" + monitorThreadName(monitor.owner) + " count=" + monitor.count
                    + " by=" + monitorThreadName(thread));
            traceMonitor(monitor, "WAIT m" + monitor.traceId + " obj=" + safeObjectClass(monitor)
                    + " owner=" + monitorThreadName(monitor.owner)
                    + " count=" + monitor.count + " by=" + monitorThreadName(thread)
                    + " stack=" + BrowserRuntime.captureStack());
            MonitorRegistry.step("wait-recorded");
        } catch (Throwable t) {
            MonitorRegistry.step("diagnostics-error");
            try {
                MonitorRegistry.callbackError("diagnostics " + errorText(t));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Finishes a contended monitor enter that vanished inside its enqueue block.
     * Called by the boot watchdog, never from the monitor path: the fiber recorded
     * the pre-enqueue checkpoint, so no waiter slot and no callback completion ever
     * happened. Granting the lock and completing the callback resumes it.
     */
    public static boolean recoverStalledEnter(Object monitorObject) {
        if (!(monitorObject instanceof Monitor)) {
            return false;
        }
        Monitor monitor = (Monitor) monitorObject;
        AsyncCallback<Void> callback = monitor.pendingCallback;
        if (callback == null) {
            return false;
        }
        TThread thread = monitor.pendingThread;
        Object fiber = monitor.pendingFiber;
        int fiberId = monitor.pendingFiberId;
        int count = monitor.pendingCount > 0 ? monitor.pendingCount : 1;
        monitor.pendingCallback = null;
        monitor.pendingThread = null;
        monitor.pendingFiber = null;
        monitor.pendingFiberId = -1;
        monitor.count += count;
        TThread owner = thread != null ? thread : TThread.currentThread();
        setOwner(monitor, owner, fiber, fiberId);
        MonitorRegistry.forcedGrant(monitor.traceId, monitor.ownerFiberId, monitor.count);
        traceMonitor(monitor, "FORCE-GRANT m" + monitor.traceId + " obj=" + safeObjectClass(monitor)
                + " to=" + monitorThreadName(owner) + " count=" + monitor.count);
        try {
            callback.complete(null);
        } catch (Throwable t) {
            try {
                MonitorRegistry.callbackError("force-grant " + errorText(t));
            } catch (Throwable ignored) {
            }
            callback.error(t);
        }
        return true;
    }

    private static String errorText(Throwable t) {
        try {
            String message = t.getMessage();
            return t.getClass().getName() + (message != null ? ": " + message : "");
        } catch (Throwable ignored) {
            return "?";
        }
    }

    static void monitorExit(TObject o) {
        monitorExit(o, 1);
    }

    static void monitorExit(TObject o, int count) {
        if (o.isEmptyMonitor()) {
            throw new TIllegalMonitorStateException();
        }

        Monitor monitor = o.monitor;
        if (monitor.owner != TThread.currentThread()) {
            // A fiber's Java thread instance can differ after a suspension. If the
            // same cooperative fiber owns the lock, releasing it is correct; only a
            // genuinely foreign thread is an error. Without this, a lock acquired
            // before an await would never be released and every later contender
            // would hang forever.
            if (monitor.ownerFiber == null || monitor.ownerFiber != currentFiber()) {
                throw new TIllegalMonitorStateException();
            }
            traceMonitor(monitor, "RELEASE-THREAD-DRIFT m" + monitor.traceId + " owner="
                    + monitorThreadName(monitor.owner) + " by=" + monitorThreadName(TThread.currentThread()));
            monitor.owner = TThread.currentThread();
            monitor.ownerFiberId = currentFiberId();
        }

        MonitorRegistry.exitCall();
        monitor.count -= count;
        if (monitor.count > 0) {
            MonitorRegistry.owner(monitor.entry, monitorThreadName(monitor.owner), monitor.ownerFiberId,
                    monitor.count, waiterCount(monitor));
            return;
        }

        clearOwner(monitor);
        if (monitor.enteringThreads != null && !monitor.enteringThreads.isEmpty()) {
            traceMonitor(monitor, "RELEASE m" + monitor.traceId + " obj=" + safeObjectClass(monitor) + " by="
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
            int remaining = enteringThreads.size();
            PlatformRunnable r = enteringThreads.remove();
            // Keep the remaining contenders queued. Clearing this queue after
            // granting only one waiter silently strands every other fiber that
            // was waiting for the same monitor; the next owner release is
            // responsible for granting the next waiter.
            if (enteringThreads.isEmpty()) {
                monitor.enteringThreads = null;
            }
            try {
                MonitorRegistry.handoff(monitor.traceId, remaining, true);
            } catch (Throwable ignored) {
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
                && monitor.waiting == 0
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
        monitor.waitGeneration++;
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
        monitor.waitGeneration++;
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
        int lockCount = monitor.count;
        int generation = monitor.waitGeneration;
        boolean timed = timeout > 0 || nanos > 0;
        long deadline = timed
                ? System.currentTimeMillis() + (timeout > 0 ? timeout : 0L) + (nanos > 0 ? 1L : 0L)
                : 0L;
        boolean interrupted = false;
        Throwable failure = null;

        // Keep the monitor object alive for the whole wait. Without this the
        // owner's release can delete the monitor while the waiter is polling,
        // and the notifier would then bump a brand-new monitor's generation.
        ++monitor.waiting;
        try {
            monitorExit(this, lockCount);
        } catch (Throwable t) {
            --monitor.waiting;
            abandonMonitor(this);
            try {
                MonitorRegistry.callbackError("wait exit " + errorText(t));
            } catch (Throwable ignored) {
            }
            callback.error(t);
            return;
        }

        try {
            // Poll the notify generation instead of parking on a monitor
            // callback. TeaVM's Wasm-GC Object.wait timer/notify handoff can
            // lose the resume and leave the render thread suspended forever
            // (seen in LockSupport.parkNanos during world load). Thread.sleep
            // uses the working async timer path; spurious wakeups are legal.
            while (monitor.waitGeneration == generation) {
                if (timed) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        break;
                    }
                    TThread.sleep(left > 2 ? 2 : left);
                } else {
                    TThread.sleep(2);
                }
            }
        } catch (TInterruptedException e) {
            interrupted = true;
        } catch (Throwable t) {
            failure = t;
        }

        --monitor.waiting;
        try {
            monitorEnter(this, lockCount);
        } catch (Throwable t) {
            abandonMonitor(this);
            callback.error(t);
            return;
        }

        if (failure != null) {
            callback.error(failure);
        } else if (interrupted) {
            callback.error(new TInterruptedException());
        } else {
            callback.complete(null);
        }
    }

    /** Drops ownership of a monitor whose waiter failed before parking cleanly. */
    private static void abandonMonitor(TObject o) {
        try {
            Monitor monitor = o.monitor;
            if (monitor == null || monitor.owner == null) {
                return;
            }
            monitor.owner = TThread.currentThread();
            monitor.ownerFiber = currentFiber();
            monitor.ownerFiberId = currentFiberId();
            monitor.count = 0;
            clearOwner(monitor);
            if (monitor.enteringThreads != null && !monitor.enteringThreads.isEmpty()) {
                EventQueue.offer(() -> waitForOtherThreads(o));
            } else {
                o.isEmptyMonitor();
            }
        } catch (Throwable ignored) {
        }
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

        // EventQueue callbacks do not necessarily execute with the suspended
        // waiter's Fiber.current() installed. Preserve the original identity so
        // the monitor is reacquired by the same cooperative fiber that released it.
        final Object waitingFiber;
        final int waitingFiberId;

        NotifyListenerImpl(TObject obj, AsyncCallback<Void> callback, int lockCount) {
            this.obj = obj;
            this.callback = callback;
            this.lockCount = lockCount;
            this.waitingFiber = currentFiber();
            this.waitingFiberId = currentFiberId();
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
            monitorEnterWait(obj, lockCount, callback, waitingFiber, waitingFiberId);
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
