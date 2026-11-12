package java.net;

/**
 * Compatibility representation of the standard Java connection exception.
 * Keeps the JDK inheritance contract: ConnectException -> SocketException
 * -> IOException.
 */
public class ConnectException extends SocketException {
    private static final long serialVersionUID = 3831404271622369215L;

    public ConnectException() {
        super();
    }

    public ConnectException(String message) {
        super(message);
    }

    public ConnectException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConnectException(Throwable cause) {
        super(cause);
    }
}
