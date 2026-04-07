package com.rainnov.framework.net.server;

import com.google.protobuf.InvalidProtocolBufferException;
import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
import com.rainnov.framework.net.queue.*;
import com.rainnov.framework.net.session.GameSession;
import com.rainnov.framework.net.session.SessionManager;
import com.rainnov.framework.proto.GameMessageProto.GameMessage;
import com.rainnov.framework.proto.GameMessageProto.C1_HeartbeatReq;
import com.rainnov.framework.proto.GameMessageProto.C2_HeartbeatResp;
import com.rainnov.framework.proto.MsgId;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 消息分发器：从 GameMessage 中提取 msgId，完成鉴权校验并将消息投入对应队列。
 * 处理连接生命周期（channelActive/channelInactive）和心跳超时。
 */
@Slf4j
@Component
@ChannelHandler.Sharable
public class MessageDispatcher extends SimpleChannelInboundHandler<GameMessage> {

    private final SessionManager sessionManager;
    private final MsgControllerRegistry msgControllerRegistry;
    private final SharedQueueManager sharedQueueManager;
    private final ServerMetrics serverMetrics;

    @Autowired(required = false)
    private DistributedQueueManager distributedQueueManager;

    @Autowired(required = false)
    private GroupKeyResolver groupKeyResolver;

    private volatile boolean shuttingDown = false;

    public MessageDispatcher(SessionManager sessionManager,
                             MsgControllerRegistry msgControllerRegistry,
                             SharedQueueManager sharedQueueManager,
                             ServerMetrics serverMetrics) {
        this.sessionManager = sessionManager;
        this.msgControllerRegistry = msgControllerRegistry;
        this.sharedQueueManager = sharedQueueManager;
        this.serverMetrics = serverMetrics;
    }

    // ─── 9.1: channelActive ─────────────────────────────────────────────────────

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        sessionManager.createSession(ctx.channel());
        ctx.fireChannelActive();
    }

    // ─── 9.2: channelInactive ───────────────────────────────────────────────────

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        sessionManager.removeSession(ctx.channel());
        ctx.fireChannelInactive();
    }

    // ─── 9.3 & 9.4 & 9.5: channelRead0 核心路由逻辑 ────────────────────────────

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GameMessage msg) {
        GameSession session = sessionManager.getByChannel(ctx.channel());
        if (session == null) return;

        session.setLastActiveTime(System.currentTimeMillis());

        // 9.5: 停机状态检查
        if (shuttingDown) return;

        // 限流检查
        if (!session.tryAcquireRateLimit()) {
            // 限流丢弃，更新指标
            serverMetrics.messageDropped();
            return;
        }

        // 限流通过，记录消息接收
        serverMetrics.messageReceived();

        int msgId = msg.getMsgId();

        // 心跳短路：不入业务队列，直接在 IO 线程回复
        if (msgId == MsgId.SYSTEM.HEARTBEAT_REQ) {
            try {
                C1_HeartbeatReq req = C1_HeartbeatReq.parseFrom(msg.getPayload());
                C2_HeartbeatResp resp = C2_HeartbeatResp.newBuilder()
                        .setTimestamp(req.getTimestamp())
                        .build();
                session.send(GameMessage.newBuilder()
                        .setMsgId(2)
                        .setSeq(msg.getSeq())
                        .setPayload(resp.toByteString())
                        .build());
            } catch (InvalidProtocolBufferException e) {
                log.warn("心跳消息解析失败: channel={}", ctx.channel().remoteAddress(), e);
                session.send(buildErrorResponse(msg, 400));
            }
            return;
        }

        // 鉴权校验
        MsgControllerRegistry.MethodInvoker invoker = msgControllerRegistry.find(msgId);
        if (invoker != null && invoker.requireAuth() && !session.isAuthenticated()) {
            session.send(buildErrorResponse(msg, 401));
            return;
        }
        if (invoker == null && !session.isAuthenticated()) {
            session.send(buildErrorResponse(msg, 401));
            return;
        }

        // 根据 groupType 决定入队目标
        if (invoker != null && invoker.groupType() != GroupType.USER) {
            GroupType groupType = invoker.groupType();

            if (groupType == GroupType.TEAM_DISTRIBUTED || groupType == GroupType.GUILD_DISTRIBUTED) {
                if (distributedQueueManager != null && groupKeyResolver != null) {
                    String groupKey = groupKeyResolver.resolve(session, groupType);
                    if (groupKey != null) {
                        distributedQueueManager.enqueue(groupKey, new GroupMessage(session, msg));
                        return;
                    }
                    // 9.4: groupKey 为 null 降级到用户队列
                    log.debug("groupKey 解析为 null，降级到用户队列: sessionId={}, msgId={}",
                            session.getSessionId(), msgId);
                }
            } else {
                // TEAM / GUILD — 单进程共享队列
                if (groupKeyResolver != null) {
                    String groupKey = groupKeyResolver.resolve(session, groupType);
                    if (groupKey != null) {
                        sharedQueueManager.getOrCreate(groupKey).enqueue(new GroupMessage(session, msg));
                        return;
                    }
                    // 9.4: groupKey 为 null 降级到用户队列
                    log.debug("groupKey 解析为 null，降级到用户队列: sessionId={}, msgId={}",
                            session.getSessionId(), msgId);
                }
            }
        }

        // 默认：投入用户专属队列
        session.enqueue(msg);
    }

    // ─── 9.6: userEventTriggered 心跳超时处理 ───────────────────────────────────

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleStateEvent) {
            if (idleStateEvent.state() == IdleState.READER_IDLE) {
                log.info("心跳超时，关闭连接: channel={}", ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
        }
        super.userEventTriggered(ctx, evt);
    }

    // ─── 9.5: 停机标志设置 ──────────────────────────────────────────────────────

    /**
     * 设置停机标志（优雅停机时由 NettyServer 调用）。
     */
    public void setShuttingDown(boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }

    // ─── 辅助方法 ────────────────────────────────────────────────────────────────

    /**
     * 构建错误响应 GameMessage。
     */
    private GameMessage buildErrorResponse(GameMessage request, int errorCode) {
        return GameMessage.newBuilder()
                .setMsgId(request.getMsgId())
                .setSeq(request.getSeq())
                .setErrorCode(errorCode)
                .build();
    }
}
