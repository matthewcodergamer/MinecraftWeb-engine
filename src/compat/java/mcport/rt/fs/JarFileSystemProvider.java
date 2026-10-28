package mcport.rt.fs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mcport.rt.ClasspathResources;

/**
 * "jar" scheme provider. Minecraft mounts its own client jar through it (VanillaPackResourcesBuilder via
 * FileSystemUtil.safeGetPath on the ".mcassetsroot" probe URL); that jar is served from the resource archive
 * the host page loaded (ArchiveFileSystem). Zip resource packs from the game directory are not supported yet.
 */
public class JarFileSystemProvider extends FileSystemProvider {
    static final JarFileSystemProvider INSTANCE = new JarFileSystemProvider();
    private static final String CLIENT_JAR = "file:/minecraft-client.jar";

    @Override
    public String getScheme() {
        return "jar";
    }

    private static ArchiveFileSystem fileSystem(URI uri) {
        String spec = uri.getRawSchemeSpecificPart();
        int bang = spec.indexOf("!/");
        String jar = bang < 0 ? spec : spec.substring(0, bang);
        if (!jar.equals(CLIENT_JAR) && !jar.equals("file:///minecraft-client.jar")) {
            throw new UnsupportedOperationException("Only the client jar can be mounted in the browser port: " + uri);
        }
        return ArchiveFileSystem.CLIENT_JAR;
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        return fileSystem(uri);
    }

    @Override
    public FileSystem newFileSystem(Path path, Map<String, ?> env) {
        throw new UnsupportedOperationException("zip resource packs are not supported in the browser port yet: " + path);
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        return fileSystem(uri);
    }

    @Override
    public Path getPath(URI uri) {
        ArchiveFileSystem fs = fileSystem(uri);
        String spec = uri.getSchemeSpecificPart();
        int bang = spec.indexOf("!/");
        return fs.getPath(bang < 0 ? "/" : spec.substring(bang + 1));
    }

    private static ArchivePath archive(Path path) {
        if (!(path instanceof ArchivePath)) {
            throw new java.nio.file.ProviderMismatchException();
        }
        return (ArchivePath) path;
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        String key = archive(path).toAbsolutePath().normalize().toString().substring(1);
        byte[] data = ClasspathResources.bytes(key);
        if (data == null) {
            throw new NoSuchFileException(path.toString());
        }
        return new ByteArrayInputStream(data);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) {
        throw new UnsupportedOperationException("use Files.newInputStream on archive paths");
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        ArchivePath p = archive(dir);
        String key = p.toAbsolutePath().normalize().toString().substring(1);
        if (!ArchiveFileSystem.isDirectory(key)) {
            if (ArchiveFileSystem.isFile(key)) {
                throw new NotDirectoryException(dir.toString());
            }
            throw new NoSuchFileException(dir.toString());
        }
        List<Path> entries = new ArrayList<>();
        for (String child : ArchiveFileSystem.list(key)) {
            Path c = p.resolve(child);
            if (filter == null || filter.accept(c)) {
                entries.add(c);
            }
        }
        return new DirectoryStream<Path>() {
            @Override
            public Iterator<Path> iterator() {
                return entries.iterator();
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void delete(Path path) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public boolean isSameFile(Path path, Path path2) {
        return path.toAbsolutePath().normalize().equals(path2.toAbsolutePath().normalize());
    }

    @Override
    public boolean isHidden(Path path) {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) {
        throw new UnsupportedOperationException();
    }

    private static ArchiveAttributes attributes(Path path) throws NoSuchFileException {
        String key = archive(path).toAbsolutePath().normalize().toString().substring(1);
        if (ArchiveFileSystem.isFile(key)) {
            return new ArchiveAttributes(false, ArchiveFileSystem.size(key));
        }
        if (ArchiveFileSystem.isDirectory(key)) {
            return new ArchiveAttributes(true, 0);
        }
        throw new NoSuchFileException(path.toString());
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        attributes(path);
        for (AccessMode m : modes) {
            if (m == AccessMode.WRITE) {
                throw new AccessDeniedException(path.toString());
            }
        }
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        return (A) attributes(path);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        ArchiveAttributes a = attributes(path);
        Map<String, Object> m = new HashMap<>();
        m.put("size", a.size());
        m.put("isDirectory", a.isDirectory());
        m.put("isRegularFile", a.isRegularFile());
        m.put("lastModifiedTime", a.lastModifiedTime());
        return m;
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        throw new ReadOnlyFileSystemException();
    }
}
