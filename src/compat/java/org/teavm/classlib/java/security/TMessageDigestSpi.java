package org.teavm.classlib.java.security;

/** java.security.MessageDigestSpi. */
public abstract class TMessageDigestSpi implements Cloneable {
    protected int engineGetDigestLength() {
        return 0;
    }

    protected abstract void engineUpdate(byte input);

    protected abstract void engineUpdate(byte[] input, int offset, int len);

    protected abstract byte[] engineDigest();

    protected abstract void engineReset();

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}
