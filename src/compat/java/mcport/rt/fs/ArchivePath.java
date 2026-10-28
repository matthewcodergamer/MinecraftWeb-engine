package mcport.rt.fs;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Path in an ArchiveFileSystem ("/assets/minecraft/..."). Immutable; names never contain '/'. */
public final class ArchivePath implements Path {
    private final ArchiveFileSystem fs;
    private final boolean absolute;
    private final String[] names;

    ArchivePath(ArchiveFileSystem fs, boolean absolute, String[] names) {
        this.fs = fs;
        this.absolute = absolute;
        this.names = names;
    }

    static ArchivePath parse(ArchiveFileSystem fs, String s) {
        boolean abs = s.startsWith("/");
        List<String> parts = new ArrayList<>();
        for (String p : s.split("/")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        return new ArchivePath(fs, abs, parts.toArray(new String[0]));
    }

    /** Key in the resource index: path without the leading '/'. */
    String key() {
        return String.join("/", names);
    }

    private static ArchivePath check(Path other) {
        if (!(other instanceof ArchivePath)) {
            throw new java.nio.file.ProviderMismatchException();
        }
        return (ArchivePath) other;
    }

    @Override public FileSystem getFileSystem() { return fs; }
    @Override public boolean isAbsolute() { return absolute; }
    @Override public Path getRoot() { return absolute ? fs.root : null; }

    @Override
    public Path getFileName() {
        return names.length == 0 ? null : new ArchivePath(fs, false, new String[] {names[names.length - 1]});
    }

    @Override
    public Path getParent() {
        if (names.length == 0 || (names.length == 1 && !absolute)) {
            return names.length == 1 ? null : null;
        }
        return new ArchivePath(fs, absolute, Arrays.copyOf(names, names.length - 1));
    }

    @Override public int getNameCount() { return names.length; }

    @Override
    public Path getName(int index) {
        return new ArchivePath(fs, false, new String[] {names[index]});
    }

    @Override
    public Path subpath(int begin, int end) {
        return new ArchivePath(fs, false, Arrays.copyOfRange(names, begin, end));
    }

    @Override
    public boolean startsWith(Path other) {
        ArchivePath o = check(other);
        if (o.absolute != absolute || o.names.length > names.length) {
            return false;
        }
        for (int i = 0; i < o.names.length; i++) {
            if (!o.names[i].equals(names[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(fs.getPath(other));
    }

    @Override
    public boolean endsWith(Path other) {
        ArchivePath o = check(other);
        if (o.absolute) {
            return equals(o);
        }
        if (o.names.length > names.length) {
            return false;
        }
        for (int i = 0; i < o.names.length; i++) {
            if (!o.names[o.names.length - 1 - i].equals(names[names.length - 1 - i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean endsWith(String other) {
        return endsWith(fs.getPath(other));
    }

    @Override
    public Path normalize() {
        List<String> out = new ArrayList<>();
        for (String n : names) {
            if (n.equals(".")) {
                continue;
            }
            if (n.equals("..") && !out.isEmpty() && !out.get(out.size() - 1).equals("..")) {
                out.remove(out.size() - 1);
            } else if (!(n.equals("..") && absolute)) {
                out.add(n);
            }
        }
        return new ArchivePath(fs, absolute, out.toArray(new String[0]));
    }

    @Override
    public Path resolve(Path other) {
        ArchivePath o = check(other);
        if (o.absolute) {
            return o;
        }
        String[] r = Arrays.copyOf(names, names.length + o.names.length);
        System.arraycopy(o.names, 0, r, names.length, o.names.length);
        return new ArchivePath(fs, absolute, r);
    }

    @Override
    public Path resolve(String other) {
        return resolve(ArchivePath.parse(fs, other));
    }

    @Override
    public Path resolveSibling(Path other) {
        Path parent = getParent();
        return parent == null ? other : parent.resolve(other);
    }

    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(fs.getPath(other));
    }

    @Override
    public Path relativize(Path other) {
        ArchivePath o = check(other);
        int common = 0;
        while (common < names.length && common < o.names.length && names[common].equals(o.names[common])) {
            common++;
        }
        List<String> r = new ArrayList<>();
        for (int i = common; i < names.length; i++) {
            r.add("..");
        }
        r.addAll(Arrays.asList(o.names).subList(common, o.names.length));
        return new ArchivePath(fs, false, r.toArray(new String[0]));
    }

    @Override
    public URI toUri() {
        return URI.create("jar:file:/minecraft-client.jar!/" + key());
    }

    @Override public Path toAbsolutePath() { return absolute ? this : new ArchivePath(fs, true, names); }
    @Override public Path toRealPath(LinkOption... options) { return toAbsolutePath().normalize(); }

    @Override
    public java.io.File toFile() {
        throw new UnsupportedOperationException("archive paths have no java.io.File");
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Iterator<Path> iterator() {
        List<Path> l = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            l.add(getName(i));
        }
        return l.iterator();
    }

    @Override
    public int compareTo(Path other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ArchivePath && ((ArchivePath) o).absolute == absolute && Arrays.equals(((ArchivePath) o).names, names);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(names) + (absolute ? 1 : 0);
    }

    @Override
    public String toString() {
        return (absolute ? "/" : "") + key();
    }
}
