package io.netty.channel.kqueue;

/**
 * Browser shim for Netty's native kqueue transport. See
 * {@code io.netty.channel.epoll.Epoll} for why the holder needs this class.
 */
public final class KQueue {
    private KQueue() {
    }

    public static boolean isAvailable() {
        return false;
    }
}
