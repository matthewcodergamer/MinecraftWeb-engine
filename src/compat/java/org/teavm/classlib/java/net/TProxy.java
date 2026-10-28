package org.teavm.classlib.java.net;

/** java.net.Proxy (value type only: the browser cannot route sockets through proxies). */
public class TProxy {
    public enum Type { DIRECT, HTTP, SOCKS }

    public static final TProxy NO_PROXY = new TProxy();

    private final Type type;
    private final Object sa;

    private TProxy() {
        type = Type.DIRECT;
        sa = null;
    }

    public TProxy(Type type, java.net.SocketAddress sa) {
        if (type == Type.DIRECT || sa == null) {
            throw new IllegalArgumentException("type " + type + " is not compatible with address " + sa);
        }
        this.type = type;
        this.sa = sa;
    }

    public Type type() {
        return type;
    }

    public java.net.SocketAddress address() {
        return (java.net.SocketAddress) sa;
    }

    @Override
    public String toString() {
        return type == Type.DIRECT ? "DIRECT" : type + " @ " + sa;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TProxy)) {
            return false;
        }
        TProxy p = (TProxy) obj;
        return p.type == type && java.util.Objects.equals(p.sa, sa);
    }

    @Override
    public int hashCode() {
        return type.hashCode() + (sa == null ? 0 : sa.hashCode());
    }
}
