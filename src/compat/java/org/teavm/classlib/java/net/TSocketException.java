package org.teavm.classlib.java.net;

import java.io.IOException;

/** java.net.SocketException. */
public class TSocketException extends IOException {
    public TSocketException() {
    }

    public TSocketException(String msg) {
        super(msg);
    }

    public TSocketException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public TSocketException(Throwable cause) {
        super(cause);
    }
}
