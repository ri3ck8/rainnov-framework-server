package com.rainnov.client;

import com.rainnov.framework.proto.GameMessageProto.GameMessage;
import com.rainnov.framework.proto.GameMessageProto.C1_HeartbeatReq;
import com.rainnov.framework.proto.GameMessageProto.C2_HeartbeatResp;
import com.rainnov.framework.proto.LoginProto.C1001_LoginReq;
import com.rainnov.framework.proto.LoginProto.C1002_LoginResp;
import com.rainnov.framework.proto.LoginProto.C1004_LogoutResp;
import com.rainnov.framework.proto.InventoryProto.C5001_QueryInventoryReq;
import com.rainnov.framework.proto.InventoryProto.C5002_QueryInventoryResp;
import com.rainnov.framework.proto.InventoryProto.InventorySlot;
import com.rainnov.framework.proto.MsgId;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 简易游戏客户端：WebSocket + Protobuf。
 * 支持 30s 心跳、控制台登录/登出。
 */
public class GameClient {

    private static final String URI_STR = "ws://localhost:8888/ws";
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private static Channel channel;
    private static ScheduledExecutorService heartbeatScheduler;

    public static void main(String[] args) throws Exception {
        URI uri = new URI(URI_STR);
        EventLoopGroup group = new NioEventLoopGroup(1);

        WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders());

        ClientHandler handler = new ClientHandler(handshaker);

        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
             .channel(NioSocketChannel.class)
             .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ch.pipeline().addLast(
                             new HttpClientCodec(),
                             new HttpObjectAggregator(65536),
                             handler
                     );
                 }
             });

            channel = b.connect(uri.getHost(), uri.getPort()).sync().channel();
            handler.handshakeFuture().sync();
            System.out.println("已连接到服务器: " + URI_STR);

            // 启动 30s 心跳
            startHeartbeat();

            // 控制台交互
            System.out.println("命令: login <token> | logout | bag | quit");
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("login ")) {
                    String token = line.substring(6).trim();
                    sendLogin(token);
                } else if ("logout".equals(line)) {
                    sendLogout();
                } else if ("bag".equals(line)) {
                    sendQueryInventory();
                } else if ("quit".equals(line)) {
                    break;
                } else {
                    System.out.println("未知命令。可用: login <token> | logout | bag | quit");
                }
            }
        } finally {
            if (heartbeatScheduler != null) heartbeatScheduler.shutdownNow();
            group.shutdownGracefully();
        }
    }

    private static void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (channel != null && channel.isActive()) {
                long ts = System.currentTimeMillis();
                C1_HeartbeatReq req = C1_HeartbeatReq.newBuilder().setTimestamp(ts).build();
                GameMessage msg = GameMessage.newBuilder()
                        .setMsgId(MsgId.SYSTEM.HEARTBEAT_REQ)
                        .setSeq(SEQ.incrementAndGet())
                        .setPayload(req.toByteString())
                        .build();
                sendMsg(msg);
                System.out.println("[心跳] 发送 timestamp=" + ts);
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    private static void sendLogin(String token) {
        C1001_LoginReq req = C1001_LoginReq.newBuilder().setToken(token).build();
        GameMessage msg = GameMessage.newBuilder()
                .setMsgId(MsgId.LOGIN.LOGIN_REQ)
                .setSeq(SEQ.incrementAndGet())
                .setPayload(req.toByteString())
                .build();
        sendMsg(msg);
        System.out.println("[登录] 发送 token=" + token);
    }

    private static void sendQueryInventory() {
        C5001_QueryInventoryReq req = C5001_QueryInventoryReq.newBuilder().build();
        GameMessage msg = GameMessage.newBuilder()
                .setMsgId(MsgId.INVENTORY.QUERY_INVENTORY_REQ)
                .setSeq(SEQ.incrementAndGet())
                .setPayload(req.toByteString())
                .build();
        sendMsg(msg);
        System.out.println("[背包] 查询已发送");
    }

    private static void sendLogout() {
        GameMessage msg = GameMessage.newBuilder()
                .setMsgId(MsgId.LOGIN.LOGOUT_REQ)
                .setSeq(SEQ.incrementAndGet())
                .build();
        sendMsg(msg);
        System.out.println("[登出] 已发送");
    }

    private static void sendMsg(GameMessage msg) {
        byte[] bytes = msg.toByteArray();
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        channel.writeAndFlush(new BinaryWebSocketFrame(buf));
    }

    /**
     * WebSocket 客户端 Handler：完成握手后处理服务端响应。
     */
    static class ClientHandler extends SimpleChannelInboundHandler<Object> {

        private final WebSocketClientHandshaker handshaker;
        private ChannelPromise handshakeFuture;

        ClientHandler(WebSocketClientHandshaker handshaker) {
            this.handshaker = handshaker;
        }

        ChannelFuture handshakeFuture() {
            return handshakeFuture;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            handshakeFuture = ctx.newPromise();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!handshaker.isHandshakeComplete()) {
                handshaker.finishHandshake(ctx.channel(), (io.netty.handler.codec.http.FullHttpResponse) msg);
                handshakeFuture.setSuccess();
                return;
            }

            if (msg instanceof BinaryWebSocketFrame frame) {
                byte[] bytes = new byte[frame.content().readableBytes()];
                frame.content().readBytes(bytes);
                GameMessage gameMsg = GameMessage.parseFrom(bytes);
                handleResponse(gameMsg);
            } else if (msg instanceof CloseWebSocketFrame) {
                System.out.println("服务端关闭连接");
                ctx.close();
            }
        }

        private void handleResponse(GameMessage msg) throws Exception {
            int msgId = msg.getMsgId();
            int errorCode = msg.getErrorCode();

            if (errorCode != 0) {
                System.out.println("[响应] msgId=" + msgId + " 错误码=" + errorCode);
                return;
            }

            if (msgId == MsgId.SYSTEM.HEARTBEAT_RESP) {
                C2_HeartbeatResp resp = C2_HeartbeatResp.parseFrom(msg.getPayload());
                long rtt = System.currentTimeMillis() - resp.getTimestamp();
                System.out.println("[心跳] 响应 timestamp=" + resp.getTimestamp() + " RTT=" + rtt + "ms");
            } else if (msgId == MsgId.LOGIN.LOGIN_RESP) {
                C1002_LoginResp resp = C1002_LoginResp.parseFrom(msg.getPayload());
                System.out.println("[登录] 成功 userId=" + resp.getUserId());
            } else if (msgId == MsgId.LOGIN.LOGOUT_RESP) {
                System.out.println("[登出] 成功");
            } else if (msgId == MsgId.INVENTORY.QUERY_INVENTORY_RESP) {
                C5002_QueryInventoryResp resp = C5002_QueryInventoryResp.parseFrom(msg.getPayload());
                System.out.println("[背包] 容量=" + resp.getCapacity() + " 已用格子=" + resp.getSlotsCount());
                for (InventorySlot slot : resp.getSlotsList()) {
                    String expireInfo = slot.getExpireTime() > 0
                            ? " 过期时间=" + slot.getExpireTime()
                            : "";
                    System.out.println("  格子[" + slot.getSlotIndex() + "] 物品=" + slot.getItemId()
                            + " 数量=" + slot.getCount() + expireInfo);
                }
            } else {
                System.out.println("[响应] msgId=" + msgId + " payload=" + msg.getPayload().size() + " bytes");
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("异常: " + cause.getMessage());
            if (!handshakeFuture.isDone()) {
                handshakeFuture.setFailure(cause);
            }
            ctx.close();
        }
    }
}
