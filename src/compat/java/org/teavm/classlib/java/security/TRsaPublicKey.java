package org.teavm.classlib.java.security;

import java.math.BigInteger;

/** RSA public key used by the browser integrated server. */
final class TRsaPublicKey implements TPublicKey {
    final BigInteger modulus;
    final BigInteger exponent;

    TRsaPublicKey(BigInteger modulus, BigInteger exponent) {
        this.modulus = modulus;
        this.exponent = exponent;
    }

    @Override
    public String getAlgorithm() {
        return "RSA";
    }

    @Override
    public String getFormat() {
        return "X.509";
    }

    @Override
    public byte[] getEncoded() {
        byte[] rsaKey = TDer.sequence(TDer.integer(modulus), TDer.integer(exponent));
        return TDer.sequence(TDer.rsaAlgorithmIdentifier(), TDer.bitString(rsaKey));
    }

    @Override
    public String toString() {
        return "RSA public key, " + modulus.bitLength() + " bits";
    }
}
