package io.netty.channel.epoll;

import io.netty.channel.socket.nio.NioSocketChannel;

/**
 * Class-literal placeholder for the native epoll stream channel.
 *
 * <p>Only {@code EventLoopGroupHolder.<clinit>} references this type, as a
 * {@code Class<? extends Channel>} constant. The native transport is never
 * selected in the browser ({@link Epoll#isAvailable()} is false), so this never
 * gets instantiated; extending the NIO channel keeps the type assignable to
 * Netty's channel API without pulling in native code.
 */
public class EpollSocketChannel extends NioSocketChannel {
}
