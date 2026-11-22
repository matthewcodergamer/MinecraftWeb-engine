package org.teavm.classlib.java.security;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Random;

/**
 * Browser replacement for {@code java.security.KeyPairGenerator}.
 *
 * <p>The integrated server creates a 1024-bit RSA key pair before loading a
 * world, even though a memory connection never performs the encrypted
 * handshake. TeaVM has no public-key implementation, so this class generates a
 * real RSA key pair with TeaVM's {@link BigInteger}. The pair is cached per key
 * size: creating several worlds should not repeat the prime search.</p>
 */
public class TKeyPairGenerator {
    private static final BigInteger PUBLIC_EXPONENT = BigInteger.valueOf(65537);
    private static TKeyPair cached;
    private static int cachedBits;

    private final String algorithm;
    private final Random random = new SecureRandom();
    private int keySize = 2048;

    private TKeyPairGenerator(String algorithm) {
        this.algorithm = algorithm;
    }

    public static TKeyPairGenerator getInstance(String algorithm) throws TNoSuchAlgorithmException {
        if (algorithm == null || !"RSA".equalsIgnoreCase(algorithm)) {
            throw new TNoSuchAlgorithmException("KeyPairGenerator " + algorithm + " not available");
        }
        return new TKeyPairGenerator("RSA");
    }

    public final String getAlgorithm() {
        return algorithm;
    }

    public void initialize(int keysize) {
        if (keysize < 512) {
            throw new IllegalArgumentException("RSA key size must be at least 512 bits");
        }
        keySize = keysize;
    }

    public void initialize(int keysize, SecureRandom random) {
        initialize(keysize);
        if (random != null) {
            // The field is final; generation still uses a SecureRandom instance,
            // so a caller-supplied generator only affects reproducibility.
        }
    }

    public synchronized TKeyPair generateKeyPair() {
        if (cached != null && cachedBits == keySize) {
            return cached;
        }
        int primeBits = (keySize + 1) / 2;
        int otherBits = keySize - primeBits;
        while (true) {
            BigInteger p = BigInteger.probablePrime(primeBits, random);
            BigInteger q = BigInteger.probablePrime(otherBits, random);
            if (p.equals(q)) {
                continue;
            }
            BigInteger modulus = p.multiply(q);
            if (modulus.bitLength() != keySize) {
                continue;
            }
            BigInteger phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE));
            if (!PUBLIC_EXPONENT.gcd(phi).equals(BigInteger.ONE)) {
                continue;
            }
            BigInteger privateExponent = PUBLIC_EXPONENT.modInverse(phi);
            BigInteger exponentP = privateExponent.mod(p.subtract(BigInteger.ONE));
            BigInteger exponentQ = privateExponent.mod(q.subtract(BigInteger.ONE));
            BigInteger coefficient = q.modInverse(p);
            TKeyPair pair = new TKeyPair(
                    new TRsaPublicKey(modulus, PUBLIC_EXPONENT),
                    new TRsaPrivateKey(modulus, PUBLIC_EXPONENT, privateExponent, p, q,
                            exponentP, exponentQ, coefficient));
            cached = pair;
            cachedBits = keySize;
            return pair;
        }
    }
}
