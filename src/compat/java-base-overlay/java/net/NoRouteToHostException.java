package java.net;

/**
 * Browser-port compatibility representation of the standard no-route-to-host
 * socket exception. Preserves the JDK exception hierarchy and constructors.
 */
public class NoRouteToHostException extends SocketException {
    private static final long serialVersionUID = -1897550894873493790L;

    public NoRouteToHostException() {
        super();
    }

    public NoRouteToHostException(String message) {
        super(message);
    }
}
