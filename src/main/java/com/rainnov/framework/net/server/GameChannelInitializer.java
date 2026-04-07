package com.rainnov.framework.net.server;

import com.rainnov.framework.net.session.SessionManager;

import com.rainnov.framework.proto.GameMessageProto.GameMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 为每个新连接配置 ChannelPipeline 处理器链。
 * 完成 WebSocket 握手升级、Protobuf 编解码、心跳检测和消息分发；
 * 在 Pipeline 最前端检查最大连接数限制。
 */
@Slf4j
@Component
public class GameChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final SessionManager sessionManager;
    private final MessageDispatcher messageDispatcher;

    @Value("${game.server.max-connections:10000}")
    private int maxConnections;

    public GameChannelInitializer(SessionManager sessionManager, MessageDispatcher messageDispatcher) {
        this.sessionManager = sessionManager;
        this.messageDispatcher = messageDispatcher;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();

        // ─── 10.2: 最前端 — 最大连接数检查（超限直接关闭，不发送任何响应）────────
        p.addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                if (sessionManager.onlineCount() >= maxConnections) {
                    log.warn("连接数已达上限 {}，拒绝新连接", maxConnections);
                    ctx.close();
                    return;
                }
                ctx.fireChannelActive();
            }
        });

        // ─── 10.1: 完整 ChannelPipeline 配置 ────────────────────────────────────

        // WebSocket 握手所需的 HTTP 编解码
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(65536));

        // WebSocket 协议升级，path 为 "/ws"
        p.addLast(new WebSocketServerProtocolHandler("/ws", null, true));

        // WebSocket BinaryFrame → ByteBuf（ProtobufDecoder 需要 ByteBuf 输入）
        p.addLast(new MessageToMessageDecoder<BinaryWebSocketFrame>() {
            @Override
            protected void decode(ChannelHandlerContext ctx, BinaryWebSocketFrame frame, List<Object> out) {
                out.add(frame.content().retain());
            }
        });

        // ByteBuf → BinaryWebSocketFrame（发送 protobuf 时包装为 WebSocket 帧）
        p.addLast(new MessageToMessageEncoder<ByteBuf>() {
            @Override
            protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
                out.add(new BinaryWebSocketFrame(msg.retain()));
            }
        });

        // Protobuf 编解码
        p.addLast(new ProtobufDecoder(GameMessage.getDefaultInstance()));
        p.addLast(new ProtobufEncoder());

        // 心跳检测：读空闲 60s
        p.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));

        // 业务分发（@Sharable，所有连接共享同一实例）
        p.addLast(messageDispatcher);
    }
}
