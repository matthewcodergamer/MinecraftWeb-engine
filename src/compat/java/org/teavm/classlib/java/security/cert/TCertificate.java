package org.teavm.classlib.java.security.cert;

/** java.security.cert.Certificate type (TLS is handled by the browser, not by Java code). */
public abstract class TCertificate implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final String type;

    protected TCertificate(String type) {
        this.type = type;
    }

    public final String getType() {
        return type;
    }

    public abstract byte[] getEncoded();
}
