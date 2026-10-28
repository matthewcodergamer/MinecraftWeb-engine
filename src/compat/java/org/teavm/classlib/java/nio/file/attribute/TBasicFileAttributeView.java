package org.teavm.classlib.java.nio.file.attribute;

import java.io.IOException;

/** TeaVM class-library extension for {@code java.nio.file.attribute.BasicFileAttributeView}. */
public interface TBasicFileAttributeView extends TFileAttributeView {
    TBasicFileAttributes readAttributes();

    void setTimes(TFileTime lastModifiedTime, TFileTime lastAccessTime, TFileTime createTime) throws IOException;
}
