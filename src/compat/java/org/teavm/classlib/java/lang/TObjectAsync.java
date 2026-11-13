package org.teavm.classlib.java.lang;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;

/**
 * Keeps asynchronous monitor callbacks outside the java.lang.Object overlay.
 *
 * TeaVM's Wasm-GC output reaches the generated Object async-call adapters but,
 * in the browser probe, does not execute their callback overloads. These
 * equivalent adapters live on an ordinary class so the generated async
 * dispatch does not collide with Object's special lowering.
 */
final class TObjectAsync {
    private TObjectAsync() {
    }

    @Async
    static native void monitorEnterWait(TObject object, int count);

    static void monitorEnterWait(TObject object, int count, AsyncCallback<Void> callback) {
        TObject.monitorEnterWait(object, count, callback);
    }

    @Async
    static native void waitImpl(TObject object, long timeout, int nanos) throws TInterruptedException;

    static void waitImpl(TObject object, long timeout, int nanos, AsyncCallback<Void> callback) {
        object.waitImpl(timeout, nanos, callback);
    }
}
