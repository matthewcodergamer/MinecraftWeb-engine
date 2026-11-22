package mcport.rt.patch;

import java.io.IOException;
import java.util.zip.GZIPOutputStream;

/**
 * TeaVM 0.16's {@code Deflater.deflate} throws when jzlib returns
 * {@code Z_BUF_ERROR} (-5), which happens when a GZIP {@code SYNC_FLUSH} fills
 * the 512-byte output buffer. NBT writers close the stream immediately after
 * flushing, so the pending bytes are emitted by {@code finish()} on close.
 * Skipping the broken sync-flush keeps level.dat and chunk saving working.
 */
public final class ZlibPatches {
    private ZlibPatches() {
    }

    public static void gzipFlush(GZIPOutputStream stream) throws IOException {
        // Intentionally empty. GZIPOutputStream.close() -> finish() emits all
        // buffered deflate output without using the broken sync-flush path.
    }
}
