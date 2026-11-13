/*
 *  Copyright 2018 Alexey Andreev.
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
package org.teavm.runtime;

import org.teavm.interop.AsyncCallback;
import org.teavm.interop.StaticInit;
import org.teavm.interop.Unmanaged;
import mcport.rt.BrowserRuntime;

@StaticInit
public final class Fiber {
    public static final int STATE_RUNNING = 0;
    public static final int STATE_SUSPENDING = 1;
    public static final int STATE_RESUMING = 2;
    public static int userThreadCount = 1;

    private int[] intValues;
    private int intTop;
    private long[] longValues;
    private int longTop;
    private float[] floatValues;
    private int floatTop;
    private double[] doubleValues;
    private int doubleTop;
    private Object[] objectValues;
    private int objectTop;
    private int state;
    private FiberRunner runner;
    private Object result;
    private Throwable exception;
    private boolean isPendingResume;
    private boolean daemon;

    private Thread owner;
    private String lastSuspend;
    private String lastResume;
    private boolean finished;
    private int id;

    private static Fiber current;
    private static PendingCall lastPendingCall;
    private static int startCount;
    private static int resumeCount;
    private static int suspendCount;
    private static int asyncCompleteCount;
    private static int asyncErrorCount;
    // Live fiber registry: every thread the client starts is one fiber, so a dump
    // of this array shows exactly which fiber is stuck and where. Allocated lazily
    // because this class is @StaticInit and may only own constant static data.
    private static Fiber[] all;
    private static int allCount;

    private Fiber(FiberRunner runner, boolean daemon) {
        this.runner = runner;
        this.daemon = daemon;
        this.id = allCount;
        if (all == null) {
            all = new Fiber[128];
        }
        if (allCount < all.length) {
            all[allCount++] = this;
        }
    }

    /**
     * Snapshot of every fiber the client has started. Used by the boot watchdog to
     * prove whether a stall is a deadlock between fibers or a lost wakeup.
     */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < allCount; ++i) {
            Fiber f = all[i];
            if (f == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append('#').append(f.id);
            sb.append(f.daemon ? "(daemon)" : "(user)");
            String name = null;
            try {
                if (f.owner != null) {
                    name = f.owner.getName();
                }
            } catch (Throwable ignored) {
            }
            sb.append(" \"").append(name != null ? name : "?").append('"');
            sb.append(" state=").append(stateName(f.state));
            if (f.finished) {
                sb.append(" FINISHED");
            }
            if (f.isPendingResume) {
                sb.append(" pendingResume");
            }
            if (f.lastSuspend != null) {
                sb.append(" suspendedAt=").append(f.lastSuspend);
            }
            if (f.lastResume != null) {
                sb.append(" lastResume=").append(f.lastResume);
            }
        }
        return sb.toString();
    }

    private static String stateName(int state) {
        switch (state) {
            case STATE_RUNNING:
                return "RUNNING";
            case STATE_SUSPENDING:
                return "SUSPENDED";
            case STATE_RESUMING:
                return "RESUMING";
            default:
                return Integer.toString(state);
        }
    }

    public void push(int value) {
        if (intValues == null) {
            intValues = new int[4];
        } else if (intTop + 1 == intValues.length) {
            int[] oldValues = intValues;
            int[] newValues = new int[oldValues.length * 3 / 2];
            for (int i = 0; i < oldValues.length; ++i) newValues[i] = oldValues[i];
            intValues = newValues;
        }
        intValues[intTop++] = value;
    }

    public void push(long value) {
        if (longValues == null) {
            longValues = new long[4];
        } else if (longTop + 1 == longValues.length) {
            long[] oldValues = longValues;
            long[] newValues = new long[oldValues.length * 3 / 2];
            for (int i = 0; i < oldValues.length; ++i) newValues[i] = oldValues[i];
            longValues = newValues;
        }
        longValues[longTop++] = value;
    }

    public void push(float value) {
        if (floatValues == null) {
            floatValues = new float[4];
        } else if (floatTop + 1 == floatValues.length) {
            float[] oldValues = floatValues;
            float[] newValues = new float[oldValues.length * 3 / 2];
            for (int i = 0; i < oldValues.length; ++i) newValues[i] = oldValues[i];
            floatValues = newValues;
        }
        floatValues[floatTop++] = value;
    }

    public void push(double value) {
        if (doubleValues == null) {
            doubleValues = new double[4];
        } else if (doubleTop + 1 == doubleValues.length) {
            double[] oldValues = doubleValues;
            double[] newValues = new double[oldValues.length * 3 / 2];
            for (int i = 0; i < oldValues.length; ++i) newValues[i] = oldValues[i];
            doubleValues = newValues;
        }
        doubleValues[doubleTop++] = value;
    }

    public void push(Object value) {
        if (objectValues == null) {
            objectValues = new Object[4];
        } else if (objectTop + 1 == objectValues.length) {
            Object[] oldValues = objectValues;
            Object[] newValues = new Object[oldValues.length * 3 / 2];
            for (int i = 0; i < oldValues.length; ++i) newValues[i] = oldValues[i];
            objectValues = newValues;
        }
        objectValues[objectTop++] = value;
    }

    public static void reversePush(int value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(long value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(float value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(double value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(Object value, Fiber fiber) {
        fiber.push(value);
    }

    public static void reversePush(PlatformObject value, Fiber fiber) {
        fiber.push(new PlatformObjectWrapper(value));
    }

    public static void reversePush(PlatformFunction value, Fiber fiber) {
        fiber.push(new PlatformFunctionWrapper(value));
    }

    public static void reversePush(PlatformException value, Fiber fiber) {
        fiber.push(new PlatformExceptionWrapper(value));
    }

    @Unmanaged
    public int popInt() {
        return intValues[--intTop];
    }

    @Unmanaged
    public long popLong() {
        return longValues[--longTop];
    }

    @Unmanaged
    public float popFloat() {
        return floatValues[--floatTop];
    }

    @Unmanaged
    public double popDouble() {
        return doubleValues[--doubleTop];
    }

    @Unmanaged
    public Object popObject() {
        Object result = objectValues[--objectTop];
        objectValues[objectTop] = null;
        return result;
    }

    public PlatformObject popPlatformObject() {
        var wrapper = (PlatformObjectWrapper) popObject();
        return wrapper.object;
    }

    public PlatformFunction popPlatformFunction() {
        var wrapper = (PlatformFunctionWrapper) popObject();
        return wrapper.object;
    }

    public PlatformException popPlatformException() {
        var wrapper = (PlatformExceptionWrapper) popObject();
        return wrapper.object;
    }

    @Unmanaged
    public static Fiber current() {
        return current;
    }

    @Unmanaged
    public boolean isSuspending() {
        return state == STATE_SUSPENDING;
    }

    @Unmanaged
    public static boolean isPresentAndSuspending(Fiber fiber) {
        return fiber != null && fiber.isSuspending();
    }

    @Unmanaged
    public boolean isResuming() {
        return state == STATE_RESUMING;
    }

    @Unmanaged
    public static boolean isPresentAndResuming(Fiber fiber) {
        return fiber != null && fiber.isResuming();
    }

    @Unmanaged
    public static boolean getBoolean(Object v) {
        return v != null ? (Boolean) v : false;
    }

    @Unmanaged
    public static byte getByte(Object v) {
        return v != null ? (Byte) v : 0;
    }

    @Unmanaged
    public static short getShort(Object v) {
        return v != null ? (Short) v : 0;
    }

    @Unmanaged
    public static int getInt(Object v) {
        return v != null ? (Integer) v : 0;
    }

    @Unmanaged
    public static char getChar(Object v) {
        return v != null ? (Character) v : 0;
    }

    @Unmanaged
    public static long getLong(Object v) {
        return v != null ? (Long) v : 0;
    }

    @Unmanaged
    public static float getFloat(Object v) {
        return v != null ? (Float) v : 0;
    }

    @Unmanaged
    public static double getDouble(Object v) {
        return v != null ? (Double) v : 0;
    }

    public static Object suspend(AsyncCall call) throws Throwable {
        Fiber fiber = current();
        if (fiber == null) {
            throw new IllegalStateException("Suspension point reached from non-threading context "
                    + "(perhaps, from native JS method). See https://teavm.org/docs/runtime/coroutines.html "
                    + "('Interaction with JavaScript' section)");
        }
        Thread javaThread = Thread.currentThread();
        if (fiber.isResuming()) {
            fiber.state = STATE_RUNNING;
            if (fiber.exception != null) {
                throw fiber.exception;
            }
            return fiber.result;
        }
        int suspendId = ++suspendCount;
        fiber.lastSuspend = call.getClass().getName();
        if (suspendId <= 100) {
            BrowserRuntime.report("trace", "fiber-" + fiber.id + "-suspend-enter");
            BrowserRuntime.report("trace", "fiber-suspend-document-state suspend_id=" + suspendId + " " + BrowserRuntime.suspendDocumentState());
            BrowserRuntime.report("trace", call.getClass().getName());
        }
        PendingCall pendingCall = new PendingCall(call, lastPendingCall);
        if (lastPendingCall != null) {
            lastPendingCall.next = pendingCall;
        }
        lastPendingCall = pendingCall;

        fiber.state = STATE_SUSPENDING;
        pendingCall.callback = new AsyncCallbackImpl(pendingCall, javaThread, fiber);
        call.run(pendingCall.callback);
        if (suspendId <= 100) {
            BrowserRuntime.report("trace", "fiber-suspend-call-return");
        }
        return null;
    }

    static class AsyncCallbackImpl implements AsyncCallback<Object> {
        PendingCall pendingCall;
        Thread javaThread;
        Fiber fiber;

        AsyncCallbackImpl(PendingCall pendingCall, Thread javaThread, Fiber fiber) {
            this.pendingCall = pendingCall;
            this.javaThread = javaThread;
            this.fiber = fiber;
        }

        @Override
        public void complete(Object result) {
            int callbackId = ++asyncCompleteCount;
            if (callbackId <= 100) {
                BrowserRuntime.report("trace", "fiber-async-callback-complete");
            }
            if (Fiber.current == this.fiber) {
                fiber.result = result;
                fiber.isPendingResume = true;
                removePendingCall();
            } else {
                setCurrentThread(javaThread);
                javaThread = null;
                Fiber fiber = this.fiber;
                this.fiber = null;
                fiber.result = result;
                removePendingCall();
                fiber.resume();
            }
        }

        @Override
        public void error(Throwable e) {
            int callbackId = ++asyncErrorCount;
            if (callbackId <= 100) {
                BrowserRuntime.report("error", "fiber-async-callback-error");
                BrowserRuntime.report("error", e.getClass().getName());
                if (e.getMessage() != null) BrowserRuntime.report("error", e.getMessage());
            }
            if (Fiber.current == this.fiber) {
                fiber.exception = e;
                fiber.isPendingResume = true;
                removePendingCall();
            } else {
                setCurrentThread(javaThread);
                javaThread = null;
                Fiber fiber = this.fiber;
                this.fiber = null;
                fiber.exception = e;
                removePendingCall();
                fiber.resume();
            }
        }

        private void removePendingCall() {
            if (pendingCall.previous != null) {
                pendingCall.previous.next = pendingCall.next;
            }
            if (pendingCall.next != null) {
                pendingCall.next.previous = pendingCall.previous;
            }
            if (pendingCall == lastPendingCall) {
                lastPendingCall = pendingCall.previous;
            }
            pendingCall = null;
        }
    }

    static native void setCurrentThread(Thread thread);

    public static void start(FiberRunner runner, boolean daemon) {
        new Fiber(runner, daemon).start();
    }

    static void startMain(String[] args) {
        BrowserRuntime.report("trace", "fiber-startMain-enter");
        start(() -> runMain(args), false);
        BrowserRuntime.report("trace", "fiber-startMain-return");
    }

    public static native void runMain(String[] args);

    private void start() {
        int traceId = ++startCount;
        if (traceId <= 20 || traceId % 100 == 0) {
            BrowserRuntime.report("trace", "fiber-" + id + "-start-enter");
        }
        Fiber former = current;
        current = this;
        if (owner == null) {
            try {
                owner = Thread.currentThread();
            } catch (Throwable ignored) {
            }
        }
        try {
            while (true) {
                if (traceId <= 20) {
                    BrowserRuntime.report("trace", "fiber-runner-before");
                }
                try {
                    runner.run();
                } catch (RuntimeException e) {
                    BrowserRuntime.report("error", "fiber-runner-runtime-exception");
                    BrowserRuntime.report("error", e.getClass().getName());
                    if (e.getMessage() != null) {
                        BrowserRuntime.report("error", e.getMessage());
                    }
                    throw e;
                } catch (Error e) {
                    BrowserRuntime.report("error", "fiber-runner-error");
                    BrowserRuntime.report("error", e.getClass().getName());
                    if (e.getMessage() != null) {
                        BrowserRuntime.report("error", e.getMessage());
                    }
                    throw e;
                }
                if (traceId <= 20) {
                    BrowserRuntime.report("trace", "fiber-runner-after");
                }
                if (!isPendingResume) {
                    break;
                }
                isPendingResume = false;
                state = STATE_RESUMING;
            }
        } finally {
            // A failed fiber must not leave itself installed as the current thread.
            current = former;
        }
        if (!isSuspending()) {
            finished = true;
        }
        if (traceId <= 20 || traceId % 100 == 0) {
            BrowserRuntime.report("trace", "fiber-" + id + (isSuspending()
                    ? "-start-return-suspended" : "-start-return-complete"));
        }
        if (!isSuspending() && !daemon && --userThreadCount == 0) {
            // Browser event scheduling is owned by the host; no native EventQueue shutdown here.
        }
    }

    void resume() {
        int traceId = ++resumeCount;
        lastResume = "from#" + (current != null ? current.id : -1);
        if (traceId <= 50 || traceId % 100 == 0) {
            BrowserRuntime.report("trace", "fiber-" + id + "-resume-enter " + lastResume);
        }
        state = STATE_RESUMING;
        start();
        if (traceId <= 50 || traceId % 100 == 0) {
            BrowserRuntime.report("trace", "fiber-" + id + "-resume-return");
        }
    }

    public interface FiberRunner {
        void run();
    }

    public interface AsyncCall {
        void run(AsyncCallback<?> callback);
    }

    static class PendingCall {
        AsyncCall value;
        PendingCall next;
        PendingCall previous;
        AsyncCallbackImpl callback;

        PendingCall(AsyncCall value, PendingCall previous) {
            this.value = value;
            this.next = null;
            this.previous = previous;
        }
    }

    public static class PlatformObjectWrapper {
        public final PlatformObject object;

        PlatformObjectWrapper(PlatformObject object) {
            this.object = object;
        }
    }

    public static class PlatformFunctionWrapper {
        public final PlatformFunction object;

        PlatformFunctionWrapper(PlatformFunction object) {
            this.object = object;
        }
    }

    public static class PlatformExceptionWrapper {
        public final PlatformException object;

        PlatformExceptionWrapper(PlatformException object) {
            this.object = object;
        }
    }

    public static class PlatformObject {
    }

    public static class PlatformFunction {
    }

    public static class PlatformException {
    }
}
