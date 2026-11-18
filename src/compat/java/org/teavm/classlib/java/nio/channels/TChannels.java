package org.teavm.classlib.java.nio.channels;

import java.io.IOException;
import org.teavm.classlib.java.io.TInputStream;
import org.teavm.classlib.java.io.TOutputStream;
import org.teavm.classlib.java.nio.TByteBuffer;

/**
 * Stream/channel adapters missing from TeaVM 0.16's class library.
 *
 * <p>Minecraft's NativeImage.read(InputStream) uses Channels.newChannel while
 * decoding its loading-screen and resource-pack textures. These adapters keep
 * that path inside TeaVM's own NIO and I/O abstractions.</p>
 */
public final class TChannels {
    private static final int BUFFER_SIZE = 8192;

    private TChannels() {
    }

    public static TReadableByteChannel newChannel(TInputStream input) {
        if (input == null) {
            throw new NullPointerException("input");
        }
        return new TReadableByteChannel() {
            private boolean open = true;

            @Override
            public int read(TByteBuffer dst) throws IOException {
                if (!open) {
                    throw new TClosedChannelException();
                }
                if (!dst.hasRemaining()) {
                    return 0;
                }
                int length = Math.min(dst.remaining(), BUFFER_SIZE);
                byte[] bytes = new byte[length];
                int count = input.read(bytes, 0, length);
                if (count > 0) {
                    for (int i = 0; i < count; ++i) {
                        dst.put(bytes[i]);
                    }
                }
                return count;
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() throws IOException {
                if (open) {
                    open = false;
                    input.close();
                }
            }
        };
    }

    public static TWritableByteChannel newChannel(TOutputStream output) {
        if (output == null) {
            throw new NullPointerException("output");
        }
        return new TWritableByteChannel() {
            private boolean open = true;

            @Override
            public int write(TByteBuffer src) throws IOException {
                if (!open) {
                    throw new TClosedChannelException();
                }
                int count = src.remaining();
                byte[] bytes = new byte[count];
                for (int i = 0; i < count; ++i) {
                    bytes[i] = src.get();
                }
                output.write(bytes, 0, count);
                return count;
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() throws IOException {
                if (open) {
                    open = false;
                    output.close();
                }
            }
        };
    }

    public static TInputStream newInputStream(TReadableByteChannel channel) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        return new TInputStream() {
            private final TByteBuffer buffer = TByteBuffer.allocate(BUFFER_SIZE);
            private boolean open = true;

            {
                buffer.limit(0);
            }

            private boolean refill() throws IOException {
                buffer.clear();
                int count;
                do {
                    count = channel.read(buffer);
                } while (count == 0);
                buffer.flip();
                return count >= 0;
            }

            @Override
            public int read() throws IOException {
                if (!open) {
                    throw new IOException("Stream closed");
                }
                if (!buffer.hasRemaining() && !refill()) {
                    return -1;
                }
                return buffer.get() & 0xFF;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                if (!open) {
                    throw new IOException("Stream closed");
                }
                if (offset < 0 || length < 0 || length > bytes.length - offset) {
                    throw new IndexOutOfBoundsException();
                }
                if (length == 0) {
                    return 0;
                }
                int total = 0;
                while (total < length) {
                    if (!buffer.hasRemaining()) {
                        if (!refill()) {
                            return total == 0 ? -1 : total;
                        }
                    }
                    int count = Math.min(length - total, buffer.remaining());
                    for (int i = 0; i < count; ++i) {
                        bytes[offset + total + i] = buffer.get();
                    }
                    total += count;
                }
                return total;
            }

            @Override
            public void close() throws IOException {
                if (open) {
                    open = false;
                    channel.close();
                }
            }
        };
    }

    public static TOutputStream newOutputStream(TWritableByteChannel channel) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        return new TOutputStream() {
            private boolean open = true;

            @Override
            public void write(int value) throws IOException {
                byte[] one = { (byte) value };
                write(one, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (!open) {
                    throw new IOException("Stream closed");
                }
                if (offset < 0 || length < 0 || length > bytes.length - offset) {
                    throw new IndexOutOfBoundsException();
                }
                TByteBuffer buffer = TByteBuffer.allocate(Math.max(length, 1));
                for (int i = 0; i < length; ++i) {
                    buffer.put(bytes[offset + i]);
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
            }

            @Override
            public void close() throws IOException {
                if (open) {
                    open = false;
                    channel.close();
                }
            }
        };
    }
}
