package org.teavm.classlib.java.security;

/**
 * Browser replacement for {@code java.security.Key}.
 *
 * <p>TeaVM's class library does not ship the public-key APIs. The integrated
 * server still creates an RSA key pair at world start, so the browser runtime
 * provides the small surface Minecraft actually links against.</p>
 */
public interface TKey {
    String getAlgorithm();

    String getFormat();

    byte[] getEncoded();
}
