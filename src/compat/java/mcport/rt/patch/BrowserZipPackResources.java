package mcport.rt.patch;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.OverlayedPackResources;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceMetadata;

/**
 * Browser-safe replacement for FilePackResources.FileResourcesSupplier.
 *
 * The original implementation converts Path to java.io.File and opens java.util.zip.ZipFile,
 * neither of which is available for browser-backed LinkFS paths. This supplier reads the
 * archive through NIO once, indexes its entries in memory, and serves independent streams.
 */
public final class BrowserZipPackResources {
    private static final int MAX_ENTRY_BYTES = 128 * 1024 * 1024;
    private static final int MAX_ARCHIVE_BYTES = 512 * 1024 * 1024;

    private BrowserZipPackResources() {
    }

    /** BODY_OBJECT target: the receiver is the private FolderPackDetector type, so accept Object. */
    public static Pack.ResourcesSupplier create(Object detector, Path archivePath) {
        try {
            byte[] archive = Files.readAllBytes(archivePath);
            if (archive.length > MAX_ARCHIVE_BYTES) {
                throw new IOException("Resource pack archive exceeds browser limit: " + archive.length);
            }
            Map<String, byte[]> entries = unzip(archive);
            return new Supplier(entries);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read browser resource-pack ZIP " + archivePath, e);
        }
    }

    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = safeEntryName(entry.getName());
                if (name == null || entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int count = 0;
                int n;
                while ((n = zip.read(buffer)) != -1) {
                    count += n;
                    total += n;
                    if (count > MAX_ENTRY_BYTES || total > MAX_ARCHIVE_BYTES) {
                        throw new IOException("Expanded resource pack exceeds browser memory limit");
                    }
                    out.write(buffer, 0, n);
                }
                entries.put(name, out.toByteArray());
                zip.closeEntry();
            }
        }
        return entries;
    }

    /** Reject absolute paths, traversal, backslashes and empty/ambiguous components. */
    private static String safeEntryName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\\\') >= 0) {
            return null;
        }
        String[] parts = name.split("/");
        for (String part : parts) {
            if (part.equals("..") || part.equals(".")) return null;
        }
        return name;
    }

    private static byte[] entry(Map<String, byte[]> entries, String name) {
        return entries.get(name);
    }

    private static IoSupplier<InputStream> stream(byte[] bytes) {
        return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
    }

    private static final class Supplier implements Pack.ResourcesSupplier {
        private final Map<String, byte[]> entries;

        Supplier(Map<String, byte[]> entries) {
            this.entries = entries;
        }

        @Override
        public net.minecraft.server.packs.PackMetadataResources openMetadata(PackLocationInfo location) {
            return new Metadata(location, entries, "");
        }

        @Override
        public Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata metadata) {
            PackResources primary = new Resources(location, entries, "");
            List<PackResources> overlays = metadata.overlays().stream()
                    .map(prefix -> (PackResources) new Resources(location, entries, prefix))
                    .toList();
            return Stream.of(new OverlayedPackResources(primary, overlays));
        }
    }

    private static class Metadata implements net.minecraft.server.packs.PackMetadataResources {
        private final PackLocationInfo location;
        private final Map<String, byte[]> entries;
        private final String prefix;
        private ResourceMetadata parsed;

        Metadata(PackLocationInfo location, Map<String, byte[]> entries, String prefix) {
            this.location = location;
            this.entries = entries;
            this.prefix = prefix;
        }

        @Override public PackLocationInfo location() { return location; }

        @Override
        public IoSupplier<InputStream> getRootResource(String... path) {
            String name = prefix + String.join("/", path);
            return stream(entry(entries, name));
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionType<T> type) throws IOException {
            if (parsed == null) {
                byte[] bytes = entry(entries, prefix + "pack.mcmeta");
                if (bytes == null) return null;
                try (InputStream in = new ByteArrayInputStream(bytes)) {
                    parsed = ResourceMetadata.fromJsonStream(in);
                }
            }
            Optional<T> section = parsed.getSection(type);
            return section.orElse(null);
        }

        @Override public void close() { }
    }

    private static final class Resources extends Metadata implements PackResources {
        private final Map<String, byte[]> entries;
        private final String prefix;

        Resources(PackLocationInfo location, Map<String, byte[]> entries, String overlay) {
            super(location, entries, overlay.isEmpty() ? "" : overlay + "/");
            this.entries = entries;
            this.prefix = overlay.isEmpty() ? "" : overlay + "/";
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
            return stream(entry(entries, prefix + type.getDirectory() + "/" + id.getNamespace()
                    + "/" + id.getPath()));
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
            String root = prefix + type.getDirectory() + "/" + namespace + "/";
            String search = root + (path.isEmpty() ? "" : path + "/");
            for (Map.Entry<String, byte[]> candidate : entries.entrySet()) {
                String name = candidate.getKey();
                if (!name.startsWith(search)) continue;
                String relative = name.substring(root.length());
                Identifier id = Identifier.tryBuild(namespace, relative);
                if (id != null) output.accept(id, stream(candidate.getValue()));
            }
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            String root = prefix + type.getDirectory() + "/";
            Set<String> result = new HashSet<>();
            for (String name : entries.keySet()) {
                if (!name.startsWith(root)) continue;
                String rest = name.substring(root.length());
                int slash = rest.indexOf('/');
                if (slash > 0) result.add(rest.substring(0, slash));
            }
            return result;
        }
    }
}
