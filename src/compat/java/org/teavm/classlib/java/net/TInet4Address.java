package org.teavm.classlib.java.net;

import java.net.UnknownHostException;

/**
 * java.net.Inet4Address for the browser class library.
 *
 * Netty casts loopback literals to Inet4Address/Inet6Address, so the base
 * InetAddress implementation must return concrete subclasses for those casts
 * to succeed at run time.
 */
public final class TInet4Address extends TInetAddress {
    TInet4Address(String hostName, byte[] address) {
        super(hostName, address);
    }

    public static TInet4Address getByAddress(String host, byte[] addr) throws UnknownHostException {
        if (addr == null || addr.length != 4) {
            throw new UnknownHostException("addr is of illegal length");
        }
        return new TInet4Address(host, addr.clone());
    }

    public static TInet4Address getByAddress(byte[] addr) throws UnknownHostException {
        return getByAddress(null, addr);
    }
}
