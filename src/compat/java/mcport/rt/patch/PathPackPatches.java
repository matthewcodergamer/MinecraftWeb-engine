package mcport.rt.patch;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.FileUtil;

/**
 * Browser replacement for {@link PathPackResources#listPath}.
 *
 * <p>The vanilla implementation lists a pack directory through
 * {@code Files.find(path, Integer.MAX_VALUE, PathPackResources::isRegularFile)}.
 * TeaVM 0.16's {@code Files.find} builds the result as
 * {@code Stream.concat(maybeNullFileStream, list(dir).flatMap(findRec))}; when the
 * predicate rejects a directory (exactly what {@code isRegularFile} does) that
 * {@code concat} is handed a {@code null} first stream, and iterating it dereferences
 * the null inside {@code SimpleStreamIterator.fetchIfNeeded}. Every vanilla pack layer
 * is a directory tree, so the client crashed while loading its critical shaders.</p>
 *
 * <p>{@code Files.walkFileTree} avoids the stream implementation entirely and is
 * verified to work on LinkFS paths in WebAssembly GC.</p>
 */
public final class PathPackPatches {
    private PathPackPatches() {
    }

    public static void listPath(String root, Path rootPath, List<String> parts,
                                PackResources.ResourceOutput output) {
        Path path;
        try {
            path = FileUtil.resolvePath(rootPath, parts);
        } catch (RuntimeException e) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        accept(root, rootPath, file, output);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    // Matches the vanilla catch blocks: a missing or non-directory
                    // pack path simply contributes no resources.
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException | NotDirectoryException e) {
            // Vanilla silently ignores packs that do not have this path.
        } catch (IOException e) {
            System.err.println("[packs] Failed to list path " + path + ": " + e);
        }
    }

    private static void accept(String root, Path rootPath, Path file, PackResources.ResourceOutput output) {
        String relative;
        try {
            relative = rootPath.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return;
        }
        Identifier id = Identifier.tryBuild(root, relative);
        if (id == null) {
            return;
        }
        output.accept(id, IoSupplier.create(file));
    }
}
