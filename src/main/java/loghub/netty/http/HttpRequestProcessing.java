package loghub.netty.http;

import java.util.function.Predicate;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import loghub.configuration.Properties;

public abstract class HttpRequestProcessing extends HttpHandler {

    protected HttpRequestProcessing() {
        super(true);
    }

    public HttpRequestProcessing(Predicate<String> urlFilter) {
        super(true, urlFilter);
    }

    public HttpRequestProcessing(Predicate<String> urlFilter, String... method) {
        super(true, urlFilter, method);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    @Override
    protected void subProcessing(FullHttpRequest request, ChannelHandlerContext ctx) throws HttpRequestFailure {
        processRequest(request, ctx);
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER);
        Properties.metrics.meter("WebServer.status.200").mark();
    }

    protected abstract void processRequest(FullHttpRequest request, ChannelHandlerContext ctx) throws HttpRequestFailure;

}
