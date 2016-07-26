package loghub.netty;

import java.net.SocketAddress;

import io.netty.bootstrap.AbstractBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoopGroup;

public abstract class ComponentFactory<BS extends AbstractBootstrap<BS,BSC>, BSC extends Channel, SA extends SocketAddress> {
    public abstract EventLoopGroup getEventLoopGroup();
    public abstract ChannelFactory<BSC> getInstance();
    public abstract AbstractBootstrap<BS,BSC> getBootStrap();
    public abstract void group();
    public abstract void finish();
    public abstract void addChildhandlers(ChannelConsumer<BS, BSC, SA> source);
    public abstract void addHandlers(ChannelConsumer<BS, BSC, SA> source);
}