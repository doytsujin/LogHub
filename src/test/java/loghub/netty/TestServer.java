package loghub.netty;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.util.CharsetUtil;
import loghub.Event;
import loghub.LogUtils;
import loghub.Pipeline;
import loghub.Tools;
import loghub.configuration.Properties;
import loghub.decoders.StringCodec;
import loghub.netty.servers.AbstractNettyServer;
import loghub.netty.servers.ServerFactory;

public class TestServer {
    private static class TesterFactory extends ServerFactory<LocalChannel, LocalAddress> {
        private static final ChannelFactory<ServerChannel> channelfactory = new ChannelFactory<ServerChannel>() {
            @Override 
            public LocalServerChannel newChannel() {
                return new LocalServerChannel();
            }
        };

        @Override
        public EventLoopGroup getEventLoopGroup() {
            return new DefaultEventLoopGroup();
        }

        @Override
        public ChannelFactory<ServerChannel> getInstance() {
            return channelfactory;
        }

    };

    private static class TesterServer extends AbstractNettyServer<TesterFactory, ServerBootstrap, ServerChannel, LocalServerChannel, LocalAddress> {
        @Override
        protected TesterFactory getNewFactory(Properties properties) {
            return new TesterFactory();
        }
    }

    private static class TesterReceiver extends NettyReceiver<TesterServer, TesterFactory, ServerBootstrap, ServerChannel, LocalServerChannel, LocalChannel, LocalAddress, Object> {

        public TesterReceiver(BlockingQueue<Event> outQueue, Pipeline pipeline) {
            super(outQueue, pipeline);
            decoder = new StringCodec();
        }

        @Override
        public LocalAddress getListenAddress() {
            return new LocalAddress(TestServer.class.getCanonicalName());
        }

        @Override
        protected TesterServer getServer() {
            return new TesterServer();
        }

        @Override
        public String getReceiverName() {
            return "ReceiverTest";
        }

        @Override
        public void addHandlers(ChannelPipeline p) {
            p.addFirst("Splitter", new LineBasedFrameDecoder(256));
            super.addHandlers(p);
            logger.debug(p);
        }

        @Override
        protected ByteBuf getContent(Object message) {
            logger.debug(message);
            return (ByteBuf) message;
        }

        @Override
        protected Object ResolveSourceAddress(ChannelHandlerContext ctx, Object message) {
            SocketAddress addr = ctx.channel().remoteAddress();
            if(addr instanceof LocalAddress) {
                return ((LocalAddress) addr).id();
            } else {
                return null;
            }
        }

        @Override
        protected boolean closeonerror() {
            return true;
        }

    }

    private static Logger logger;

    @BeforeClass
    static public void configure() throws IOException {
        Tools.configure();
        logger = LogManager.getLogger();
        LogUtils.setLevel(logger, Level.TRACE, "loghub.netty", "io.netty");
    }

    @Test(timeout=2000)
    public void testSimple() throws InterruptedException {
        Properties empty = new Properties(Collections.emptyMap());
        BlockingQueue<Event> receiver = new ArrayBlockingQueue<>(1);
        TesterReceiver r = new TesterReceiver(receiver, new Pipeline(Collections.emptyList(), "testone", null));
        r.configure(empty);

        final ChannelFuture[] sent = new ChannelFuture[1];

        EventLoopGroup workerGroup = new DefaultEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(workerGroup);
        b.channel(LocalChannel.class);
        b.handler(new SimpleChannelInboundHandler<ByteBuf>() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                sent[0] = ctx.writeAndFlush(Unpooled.copiedBuffer("Message\r\n", CharsetUtil.UTF_8));
            }
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) throws Exception {
            }

        });

        // Start the client.
        ChannelFuture f = b.connect(new LocalAddress(TestServer.class.getCanonicalName())).sync();
        Thread.sleep(100);
        r.getChannelFuture().sync();
        sent[0].sync();
        f.channel().close();

        // Wait until the connection is closed.
        f.channel().closeFuture().sync();

        Event e = receiver.poll();
        Assert.assertEquals("Message", e.get("message"));

    }
}