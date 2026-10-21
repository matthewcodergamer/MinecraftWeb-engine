package org.teavm.classlib.java.net;

import java.net.UnknownHostException;

/**
 * java.net.InetAddress for literal IPv4/IPv6 addresses. Browsers expose no DNS resolution;
 * host names are kept unresolved and reported as such.
 */
public class TInetAddress implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final String hostName;
    private final byte[] address;

    TInetAddress(String hostName, byte[] address) {
        this.hostName = hostName;
        this.address = address;
    }

    public static TInetAddress getByName(String host) throws UnknownHostException {
        if (host == null || host.isEmpty() || host.equals("localhost")) {
            return new TInetAddress("localhost", new byte[] {127, 0, 0, 1});
        }
        byte[] v4 = parseV4(host);
        if (v4 != null) {
            return new TInetAddress(null, v4);
        }
        throw new UnknownHostException(host + ": DNS resolution is not available in the browser");
    }

    public static TInetAddress getByAddress(byte[] addr) throws UnknownHostException {
        return getByAddress(null, addr);
    }

    public static TInetAddress getByAddress(String host, byte[] addr) throws UnknownHostException {
        if (addr == null || (addr.length != 4 && addr.length != 16)) {
            throw new UnknownHostException("addr is of illegal length");
        }
        return new TInetAddress(host, addr.clone());
    }

    public static TInetAddress getLoopbackAddress() {
        return new TInetAddress("localhost", new byte[] {127, 0, 0, 1});
    }

    public static TInetAddress getLocalHost() {
        return getLoopbackAddress();
    }

    private static byte[] parseV4(String s) {
        String[] p = s.split("\\.");
        if (p.length != 4) {
            return null;
        }
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(p[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                b[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return b;
    }

    public byte[] getAddress() {
        return address.clone();
    }

    public String getHostAddress() {
        if (address.length == 4) {
            return (address[0] & 255) + "." + (address[1] & 255) + "." + (address[2] & 255) + "." + (address[3] & 255);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(Integer.toHexString(((address[i] & 255) << 8) | (address[i + 1] & 255)));
        }
        return sb.toString();
    }

    public String getHostName() {
        return hostName != null ? hostName : getHostAddress();
    }

    public boolean isLoopbackAddress() {
        return address.length == 4 && address[0] == 127;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TInetAddress && java.util.Arrays.equals(address, ((TInetAddress) o).address);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(address);
    }

    @Override
    public String toString() {
        return (hostName != null ? hostName : "") + "/" + getHostAddress();
    }
}
