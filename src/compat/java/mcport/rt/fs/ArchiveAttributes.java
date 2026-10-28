package mcport.rt.fs;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

final class ArchiveAttributes implements BasicFileAttributes {
    private static final FileTime EPOCH = FileTime.fromMillis(0);
    private final boolean directory;
    private final long size;

    ArchiveAttributes(boolean directory, long size) {
        this.directory = directory;
        this.size = size;
    }

    @Override public FileTime lastModifiedTime() { return EPOCH; }
    @Override public FileTime lastAccessTime() { return EPOCH; }
    @Override public FileTime creationTime() { return EPOCH; }
    @Override public boolean isRegularFile() { return !directory; }
    @Override public boolean isDirectory() { return directory; }
    @Override public boolean isSymbolicLink() { return false; }
    @Override public boolean isOther() { return false; }
    @Override public long size() { return size; }
    @Override public Object fileKey() { return null; }
}
