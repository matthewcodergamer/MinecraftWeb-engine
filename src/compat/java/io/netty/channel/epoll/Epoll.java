package io.netty.channel.epoll;

/**
 * Browser shim for Netty's native epoll transport.
 *
 * <p>Minecraft 26.3's {@code EventLoopGroupHolder.<clinit>} references
 * {@code EpollSocketChannel.class} and calls {@code Epoll.isAvailable()}
 * unconditionally, even for the in-memory singleplayer transport. The build
 * intentionally omits the native epoll jars, so without these shims the class
 * literal fails with {@code NoClassDefFoundError} before the LOCAL holder is
 * constructed. Reporting "not available" makes vanilla fall back to NIO for
 * remote sockets and leaves the local channel path untouched.
 */
public final class Epoll {
    private Epoll() {
    }

    public static boolean isAvailable() {
        return false;
    }
}
