package mcport.rt.patch;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** ByteBuffer overloads for Inflater, implemented through TeaVM's real jzlib-backed byte-array API. */
public final class InflaterPatches {
    private InflaterPatches() {
    }

    public static void setInput(Inflater inflater, ByteBuffer input) {
        int remaining = input.remaining();
        if (remaining == 0) {
            return;
        }
        byte[] bytes = new byte[remaining];
        input.get(bytes);
        inflater.setInput(bytes, 0, bytes.length);
    }

    public static int inflate(Inflater inflater, ByteBuffer output) throws DataFormatException {
        if (output.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        int remaining = output.remaining();
        if (remaining == 0) {
            return 0;
        }
        byte[] bytes = new byte[remaining];
        int produced = inflater.inflate(bytes, 0, remaining);
        if (produced > 0) {
            output.put(bytes, 0, produced);
        }
        return produced;
    }
}
