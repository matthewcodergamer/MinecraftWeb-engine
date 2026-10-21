package mcport.rt.fs;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/**
 * "jar" scheme provider. Minecraft's Util requires one to exist at class init and later
 * uses it to mount zip resource packs. Mounting is not implemented yet (tracked in
 * docs/MIGRATION_MATRIX.md); every operation fails loudly instead of pretending.
 */
public class JarFileSystemProvider extends FileSystemProvider {
    private static UnsupportedOperationException todo() {
        return new UnsupportedOperationException("jar/zip file systems are not implemented in the browser port yet");
    }

    @Override
    public String getScheme() {
        return "jar";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        throw todo();
    }

    @Override
    public FileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
        throw todo();
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        throw todo();
    }

    @Override
    public Path getPath(URI uri) {
        throw todo();
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {
        throw todo();
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        throw todo();
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        throw todo();
    }

    @Override
    public void delete(Path path) throws IOException {
        throw todo();
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        throw todo();
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        throw todo();
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        return path.equals(path2);
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        throw todo();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        throw todo();
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        throw todo();
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        throw todo();
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
            throws IOException {
        throw todo();
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        throw todo();
    }
}
