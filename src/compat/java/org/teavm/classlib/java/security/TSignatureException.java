package org.teavm.classlib.java.security;

import org.teavm.classlib.java.lang.TThrowable;

/**
 * Browser replacement for {@code java.security.SignatureException}.
 *
 * <p>TeaVM's class library ships {@code GeneralSecurityException} but not the
 * {@code SignatureException} subclass. Without this substitute TeaVM has no
 * superclass for the catch type used by Minecraft's signature validators, so
 * the Wasm GC exception dispatch emits {@code br_on_cast} from
 * {@code java.lang.Throwable} to an unrelated root structure. Chrome rejects
 * that module with "invalid types for br_on_cast" before it can run.</p>
 */
public class TSignatureException extends TGeneralSecurityException {
    public TSignatureException() {
    }

    public TSignatureException(String message) {
        super(message);
    }

    public TSignatureException(String message, TThrowable cause) {
        super(message, cause);
    }

    public TSignatureException(TThrowable cause) {
        super(cause);
    }
}
