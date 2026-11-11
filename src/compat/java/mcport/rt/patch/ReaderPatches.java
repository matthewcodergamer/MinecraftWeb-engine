package mcport.rt.patch;

import java.io.IOException;
import java.io.Reader;

/** Java 25 Reader convenience method missing from TeaVM's class library. */
public final class ReaderPatches {
    private ReaderPatches() {
    }

    public static String readAllAsString(Reader reader) throws IOException {
        StringBuilder result = new StringBuilder();
        char[] buffer = new char[4096];
        while (true) {
            int count = reader.read(buffer, 0, buffer.length);
            if (count < 0) {
                return result.toString();
            }
            if (count == 0) {
                // Defensive progress for unusual Reader implementations.
                int one = reader.read();
                if (one < 0) {
                    return result.toString();
                }
                result.append((char) one);
            } else {
                result.append(buffer, 0, count);
            }
        }
    }
}
