package io.netty.channel;

import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.StringUtil;

/**
 * TeaVM has no reflection metadata for Netty's channel classes, so
 * {@code Class.getConstructor()} returns no constructors and the stock factory
 * throws while creating the singleplayer memory channel. The only channel
 * classes the browser port needs are Netty's in-memory pair, so instantiate
 * those directly and keep the reflective path for everything else.
 */
public class ReflectiveChannelFactory<T extends Channel> implements ChannelFactory<T> {
    private final Class<? extends T> clazz;

    public ReflectiveChannelFactory(Class<? extends T> clazz) {
        ObjectUtil.checkNotNull(clazz, "clazz");
        this.clazz = clazz;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T newChannel() {
        if (clazz == LocalChannel.class) {
            return (T) new LocalChannel();
        }
        if (clazz == LocalServerChannel.class) {
            return (T) new LocalServerChannel();
        }
        try {
            return clazz.getConstructor().newInstance();
        } catch (Throwable t) {
            throw new ChannelException("Unable to create Channel from class " + clazz, t);
        }
    }

    @Override
    public String toString() {
        return StringUtil.simpleClassName(ReflectiveChannelFactory.class) + '('
                + StringUtil.simpleClassName(clazz) + ".class)";
    }
}
