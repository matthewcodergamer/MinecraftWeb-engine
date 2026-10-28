package org.teavm.classlib.java.lang;

/** java.lang.UnsatisfiedLinkError. */
public class TUnsatisfiedLinkError extends LinkageError {
    public TUnsatisfiedLinkError() {
    }

    public TUnsatisfiedLinkError(String message) {
        super(message);
    }
}
