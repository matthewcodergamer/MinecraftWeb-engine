package org.teavm.classlib.java.net;

import java.util.Objects;

/** java.net.InetSocketAddress (unresolved host/port value; the browser has no DNS resolver). */
public class TInetSocketAddress extends TSocketAddress {
    private final String hostname;
    private final TInetAddress addr;
    private final int port;

    public TInetSocketAddress(int port) {
        this((TInetAddress) null, port);
    }

    public TInetSocketAddress(TInetAddress addr, int port) {
        this.addr = addr;
        this.hostname = null;
        this.port = checkPort(port);
    }

    public TInetSocketAddress(String hostname, int port) {
        this.hostname = Objects.requireNonNull(hostname);
        this.addr = null;
        this.port = checkPort(port);
    }

    private static int checkPort(int port) {
        if (port < 0 || port > 0xFFFF) {
            throw new IllegalArgumentException("port out of range:" + port);
        }
        return port;
    }

    public static TInetSocketAddress createUnresolved(String host, int port) {
        return new TInetSocketAddress(host, port);
    }

    public final int getPort() {
        return port;
    }

    public final TInetAddress getAddress() {
        return addr;
    }

    public final String getHostName() {
        return hostname != null ? hostname : addr != null ? addr.getHostName() : "0.0.0.0";
    }

    public final String getHostString() {
        return getHostName();
    }

    public final boolean isUnresolved() {
        return addr == null;
    }

    @Override
    public String toString() {
        return getHostName() + "/<unresolved>:" + port;
    }

    @Override
    public final boolean equals(Object obj) {
        if (!(obj instanceof TInetSocketAddress)) {
            return false;
        }
        TInetSocketAddress o = (TInetSocketAddress) obj;
        return port == o.port && getHostName().equals(o.getHostName());
    }

    @Override
    public final int hashCode() {
        return getHostName().hashCode() * 31 + port;
    }
}
