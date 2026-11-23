package org.teavm.classlib.java.net;

import java.net.UnknownHostException;

/**
 * java.net.Inet6Address for the browser class library.
 *
 * Netty creates IPv6 loopback and parsed literals through
 * Inet6Address.getByAddress(host, bytes, scopeId); return the concrete
 * subclass so its Inet6Address-typed fields and casts work under TeaVM.
 */
public final class TInet6Address extends TInetAddress {
    private final int scopeId;

    TInet6Address(String hostName, byte[] address, int scopeId) {
        super(hostName, address);
        this.scopeId = scopeId;
    }

    public static TInet6Address getByAddress(String host, byte[] addr, int scopeId) throws UnknownHostException {
        if (addr == null || addr.length != 16) {
            throw new UnknownHostException("addr is of illegal length");
        }
        return new TInet6Address(host, addr.clone(), scopeId);
    }

    public static TInet6Address getByAddress(byte[] addr) throws UnknownHostException {
        return getByAddress(null, addr, -1);
    }

    public int getScopeId() {
        return scopeId;
    }
}
