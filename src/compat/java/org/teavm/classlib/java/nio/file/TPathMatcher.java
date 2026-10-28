package org.teavm.classlib.java.nio.file;

/** java.nio.file.PathMatcher. */
@FunctionalInterface
public interface TPathMatcher {
    boolean matches(TPath path);
}
