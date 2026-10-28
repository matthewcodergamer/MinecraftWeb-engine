package org.teavm.classlib.java.net;

/** java.net.Authenticator: stores the default; nothing in the browser asks for proxy credentials. */
public abstract class TAuthenticator {
    private static TAuthenticator theAuthenticator;

    public static synchronized void setDefault(TAuthenticator a) {
        theAuthenticator = a;
    }

    public static TAuthenticator getDefault() {
        return theAuthenticator;
    }

    protected TPasswordAuthentication getPasswordAuthentication() {
        return null;
    }
}
