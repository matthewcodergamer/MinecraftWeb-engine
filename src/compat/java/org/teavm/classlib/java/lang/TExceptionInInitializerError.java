package org.teavm.classlib.java.lang;

/**
 * java.lang.ExceptionInInitializerError for the browser class library.
 *
 * Netty wraps failures of its own static initializers in this error, and TeaVM 0.16
 * does not supply the type. The constructor has to keep the cause so diagnostics
 * still surface the original failure.
 */
public class TExceptionInInitializerError extends LinkageError {
    private static final long serialVersionUID = 1L;
    private final Throwable exception;

    public TExceptionInInitializerError() {
        this((Throwable) null);
    }

    public TExceptionInInitializerError(Throwable thrown) {
        super();
        exception = thrown;
        if (thrown != null) {
            initCause(thrown);
        }
    }

    public TExceptionInInitializerError(String message) {
        super(message);
        exception = null;
    }

    public Throwable getException() {
        return exception;
    }
}
