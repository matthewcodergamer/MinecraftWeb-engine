package io.netty.util;

import io.netty.util.internal.PlatformDependent;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collection;
import java.util.Collections;

/**
 * Browser-safe replacements for Netty's host network-interface discovery.
 *
 * A browser has no JVM-visible host NetworkInterface enumeration. Keep the
 * loopback address constants usable while reporting no native interfaces.
 */
final class NetUtilInitializationsBrowser {
    private NetUtilInitializationsBrowser() {
    }

    static Collection<NetworkInterface> networkInterfaces() {
        return Collections.emptyList();
    }

    static Inet4Address createLocalhost4() {
        try {
            return (Inet4Address) InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1});
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return null;
        }
    }

    static Inet6Address createLocalhost6() {
        try {
            return (Inet6Address) InetAddress.getByAddress("localhost",
                    new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
        } catch (Exception e) {
            PlatformDependent.throwException(e);
            return null;
        }
    }

    static NetUtilInitializations.NetworkIfaceAndInetAddress determineLoopback(
            Collection<NetworkInterface> ignored, Inet4Address localhost4, Inet6Address localhost6) {
        InetAddress address = localhost4 != null ? localhost4 : localhost6;
        return new NetUtilInitializations.NetworkIfaceAndInetAddress(null, address);
    }
}
