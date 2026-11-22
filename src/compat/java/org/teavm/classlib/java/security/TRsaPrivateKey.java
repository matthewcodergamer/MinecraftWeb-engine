package org.teavm.classlib.java.security;

import java.math.BigInteger;

/** RSA private key used by the browser integrated server. */
final class TRsaPrivateKey implements TPrivateKey {
    final BigInteger modulus;
    final BigInteger publicExponent;
    final BigInteger privateExponent;
    final BigInteger primeP;
    final BigInteger primeQ;
    final BigInteger exponentP;
    final BigInteger exponentQ;
    final BigInteger coefficient;

    TRsaPrivateKey(BigInteger modulus, BigInteger publicExponent, BigInteger privateExponent,
            BigInteger primeP, BigInteger primeQ, BigInteger exponentP, BigInteger exponentQ,
            BigInteger coefficient) {
        this.modulus = modulus;
        this.publicExponent = publicExponent;
        this.privateExponent = privateExponent;
        this.primeP = primeP;
        this.primeQ = primeQ;
        this.exponentP = exponentP;
        this.exponentQ = exponentQ;
        this.coefficient = coefficient;
    }

    @Override
    public String getAlgorithm() {
        return "RSA";
    }

    @Override
    public String getFormat() {
        return "PKCS#8";
    }

    @Override
    public byte[] getEncoded() {
        byte[] rsaKey = TDer.sequence(
                TDer.integer(BigInteger.ZERO),
                TDer.integer(modulus),
                TDer.integer(publicExponent),
                TDer.integer(privateExponent),
                TDer.integer(primeP),
                TDer.integer(primeQ),
                TDer.integer(exponentP),
                TDer.integer(exponentQ),
                TDer.integer(coefficient));
        return TDer.sequence(TDer.integer(BigInteger.ZERO), TDer.rsaAlgorithmIdentifier(),
                TDer.octetString(rsaKey));
    }

    @Override
    public String toString() {
        return "RSA private key, " + modulus.bitLength() + " bits";
    }
}
