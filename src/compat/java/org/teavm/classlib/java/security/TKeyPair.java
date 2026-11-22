package org.teavm.classlib.java.security;

/** Browser replacement for {@code java.security.KeyPair}. */
public class TKeyPair {
    private final TPublicKey publicKey;
    private final TPrivateKey privateKey;

    public TKeyPair(TPublicKey publicKey, TPrivateKey privateKey) {
        if (publicKey == null || privateKey == null) {
            throw new IllegalArgumentException("Key pair components must not be null");
        }
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public TPublicKey getPublic() {
        return publicKey;
    }

    public TPrivateKey getPrivate() {
        return privateKey;
    }
}
