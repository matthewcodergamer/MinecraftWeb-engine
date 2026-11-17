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

    /** {@code Reader.transferTo(Writer)} is a Java 10 addition absent from TeaVM's library. */
    public static long transferTo(java.io.BufferedReader reader, java.io.Writer writer) throws IOException {
        return transferTo((Reader) reader, writer);
    }

    public static long transferTo(Reader reader, java.io.Writer writer) throws IOException {
        char[] buffer = new char[8192];
        long total = 0;
        while (true) {
            int count = reader.read(buffer, 0, buffer.length);
            if (count < 0) {
                return total;
            }
            if (count == 0) {
                int one = reader.read();
                if (one < 0) {
                    return total;
                }
                writer.write(one);
                total++;
            } else {
                writer.write(buffer, 0, count);
                total += count;
            }
        }
    }
}
