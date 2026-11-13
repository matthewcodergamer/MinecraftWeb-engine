package mcport.rt.patch;

import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import mcport.rt.ClasspathResources;
import mcport.rt.fs.ArchiveFileSystem;
import net.minecraft.server.packs.linkfs.LinkFileSystem;

/**
 * Browser-side replacement for {@code IndexedAssetSource.createIndexFs}. The original opens
 * {@code <assetsDir>/<index>.json} through the JDK file system, which TeaVM backs with an
 * in-memory VFS that has no game files. The port keeps the client jar's own assets in the
 * resource archive, so the index is read from there and every logical name is mapped to the
 * matching {@code assets/<name>} entry instead of the launcher's object store.
 */
public final class AssetIndexPatches {
    private AssetIndexPatches() {
    }

    public static Path createIndexFs(Path assetsDir, String indexName) {
        // IndexedAssetSource receives the index id (for example "26.3"), while
        // the resource archive preserves the launcher's canonical directory layout.
        // The previous lookup omitted "indexes/" and incorrectly requested
        // assets/26.3.json, although pack_resources.py stores assets/indexes/26.3.json.
        String key = "assets/indexes/" + indexName + ".json";
        byte[] data = ClasspathResources.bytes(key);
        if (data == null) {
            // Compatibility fallback for older resource archives that used the flat path.
            String legacyKey = "assets/" + indexName + ".json";
            data = ClasspathResources.bytes(legacyKey);
            if (data == null) {
                throw new UncheckedIOException(new NoSuchFileException(key));
            }
        }
        JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject objects = root.getAsJsonObject("objects");
        LinkFileSystem.Builder builder = LinkFileSystem.builder();
        if (objects != null) {
            for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
                String name = entry.getKey();
                List<String> parts = new ArrayList<>();
                for (String part : name.split("/")) {
                    if (!part.isEmpty()) {
                        parts.add(part);
                    }
                }
                builder.put(parts, ArchiveFileSystem.path("assets/" + name));
            }
        }
        return builder.build(indexName).getPath("/");
    }
}
