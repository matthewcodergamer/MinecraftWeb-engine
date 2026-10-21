package org.teavm.classlib.java.security.cert;

/** java.security.cert.X509Certificate type (TLS is performed by the browser). */
public abstract class TX509Certificate extends TCertificate {
    private static final long serialVersionUID = 1L;

    protected TX509Certificate() {
        super("X.509");
    }
}
