package java.net;

import java.io.IOException;

/**
 * Browser-port compatibility representation of the standard Java socket exception.
 * Socket operations are supplied by browser bridges; this class preserves the
 * JDK exception hierarchy used by Minecraft and its libraries.
 */
public class SocketException extends IOException {
    private static final long serialVersionUID = -5935874303556886934L;

    public SocketException() {
        super();
    }

    public SocketException(String message) {
        super(message);
    }

    public SocketException(String message, Throwable cause) {
        super(message, cause);
    }

    public SocketException(Throwable cause) {
        super(cause);
    }
}
