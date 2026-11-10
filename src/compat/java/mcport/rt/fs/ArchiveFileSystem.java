package mcport.rt.fs;

import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.teavm.jso.JSBody;

/**
 * Read-only file system over the client jar's resources (window.__mcResources, loaded by the host page):
 * what the JDK's zip file system provides to Minecraft's VanillaPackResources. File contents stay in the
 * browser's ArrayBuffer until read.
 */
public final class ArchiveFileSystem extends FileSystem {
    public static final ArchiveFileSystem CLIENT_JAR = new ArchiveFileSystem();

    /** Absolute path inside the client jar archive, e.g. {@code assets/minecraft/lang/en_us.json}. */
    public static java.nio.file.Path path(String key) {
        return CLIENT_JAR.getPath("/" + key);
    }
    private final JarFileSystemProvider provider = JarFileSystemProvider.INSTANCE;
    final ArchivePath root = new ArchivePath(this, true, new String[0]);

    private ArchiveFileSystem() {
    }

    // ---- archive index (built lazily in JS from the flat resource index) ----

    @JSBody(script = "var r = window.__mcResources; if (r.dirs) return;"
            + "var dirs = r.dirs = new Map(); dirs.set('', new Set());"
            + "for (var p in r.index) { var parts = p.split('/'); var d = '';"
            + "  for (var i = 0; i < parts.length; i++) { var child = parts[i]; var set = dirs.get(d);"
            + "    if (!set) { set = new Set(); dirs.set(d, set); } set.add(child);"
            + "    d = d ? d + '/' + child : child; if (i < parts.length - 1 && !dirs.has(d)) dirs.set(d, new Set()); } }")
    private static native void buildIndex();

    @JSBody(params = "p", script = "return !!window.__mcResources.dirs.get(p);")
    private static native boolean isDir0(String p);

    @JSBody(params = "p", script = "return !!window.__mcResources.index[p];")
    private static native boolean isFile0(String p);

    @JSBody(params = "p", script = "var e = window.__mcResources.index[p]; return e ? e[1] : -1;")
    private static native int size0(String p);

    @JSBody(params = "p", script = "return Array.from(window.__mcResources.dirs.get(p) || []).join('\\n');")
    private static native String list0(String p);

    static boolean isDirectory(String p) {
        buildIndex();
        return isDir0(p);
    }

    static boolean isFile(String p) {
        return isFile0(p);
    }

    static int size(String p) {
        return size0(p);
    }

    static String[] list(String p) {
        buildIndex();
        String s = list0(p);
        return s.isEmpty() ? new String[0] : s.split("\n");
    }

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return List.of(root);
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return List.of();
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        StringBuilder sb = new StringBuilder(first);
        for (String m : more) {
            if (!m.isEmpty()) {
                sb.append('/').append(m);
            }
        }
        return ArchivePath.parse(this, sb.toString());
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        int colon = syntaxAndPattern.indexOf(':');
        String syntax = syntaxAndPattern.substring(0, colon);
        String pattern = syntaxAndPattern.substring(colon + 1);
        Pattern regex = Pattern.compile("regex".equals(syntax) ? pattern : globToRegex(pattern));
        return path -> regex.matcher(path.toString()).matches();
    }

    private static String globToRegex(String glob) {
        StringBuilder r = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        r.append(".*");
                        i++;
                    } else {
                        r.append("[^/]*");
                    }
                    break;
                case '?': r.append("[^/]"); break;
                case '{': r.append("(?:"); break;
                case '}': r.append(')'); break;
                case ',': r.append('|'); break;
                case '.': case '(': case ')': case '+': case '|': case '^': case '$': case '\\':
                    r.append('\\').append(c);
                    break;
                default: r.append(c);
            }
        }
        return r.toString();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toString() {
        return "minecraft-client.jar";
    }
}
