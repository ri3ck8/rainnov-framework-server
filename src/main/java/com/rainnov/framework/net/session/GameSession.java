package com.rainnov.framework.net.session;

import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
import com.rainnov.framework.net.server.ServerMetrics;

import com.google.common.util.concurrent.RateLimiter;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.rainnov.framework.proto.GameMessageProto.GameMessage;
import io.netty.channel.Channel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 封装单个客户端连接的状态，提供发送消息的便捷方法。
 * 每个 Session 拥有独立的消息队列和专属虚拟线程，保证单用户消息串行消费。
 * 内置令牌桶限流，支持优雅停机。
 */
@Slf4j
public class GameSession {

    // ─── 4.1: 基础字段 ───────────────────────────────────────────────────────────
    @Getter
    private final String sessionId;
    @Getter
    private final Channel channel;
    @Getter
    private long userId;
    @Getter
    private boolean authenticated;
    @Getter
    private final long createTime;
    @Getter @Setter
    private long lastActiveTime;

    // GroupKeyResolver 需要的字段
    @Getter @Setter
    private Long teamId;
    @Getter @Setter
    private Long guildId;

    // ─── 4.2: 消息队列与 POISON_PILL ─────────────────────────────────────────────
    private static final GameMessage POISON_PILL = GameMessage.getDefaultInstance();
    private final LinkedBlockingQueue<GameMessage> messageQueue = new LinkedBlockingQueue<>(256);

    // ─── 4.3: 令牌桶限流 ─────────────────────────────────────────────────────────
    private final RateLimiter rateLimiter;

    // ─── 4.4: 停机标志 ──────────────────────────────────────────────────────────
    private volatile boolean acceptingMessages = true;

    // ─── 4.5 & 4.7: 消费线程 ────────────────────────────────────────────────────
    private final Thread consumerThread;
    private final MsgControllerRegistry registry;
    private final ServerMetrics serverMetrics;

    // ─── 4.7: 构造函数（启动虚拟线程）─────────────────────────────────────────────
    public GameSession(Channel channel, MsgControllerRegistry registry, double rateLimitPerSecond, ServerMetrics serverMetrics) {
        this.sessionId = UUID.randomUUID().toString();
        this.channel = channel;
        this.registry = registry;
        this.serverMetrics = serverMetrics;
        this.createTime = System.currentTimeMillis();
        this.lastActiveTime = this.createTime;
        this.rateLimiter = RateLimiter.create(rateLimitPerSecond);

        // 启动专属虚拟线程消费消息队列
        this.consumerThread = Thread.ofVirtual()
                .name("session-consumer-" + sessionId)
                .start(buildConsumerTask());
    }

    // ─── 4.3: 限流检查 ──────────────────────────────────────────────────────────
    /**
     * 非阻塞限流检查，立即返回是否获取到令牌。
     */
    public boolean tryAcquireRateLimit() {
        return rateLimiter.tryAcquire();
    }

    // ─── 4.4: 入队方法 ──────────────────────────────────────────────────────────
    /**
     * 将消息投入用户专属队列（由 Netty Worker 线程调用，非阻塞）。
     * 停机期间静默丢弃；队列满时丢弃并记录 WARN。
     */
    public void enqueue(GameMessage message) {
        if (!acceptingMessages) {
            return;
        }
        if (!messageQueue.offer(message)) {
            serverMetrics.messageDropped();
            log.warn("消息队列已满，丢弃消息: sessionId={}, msgId={}", sessionId, message.getMsgId());
        }
    }

    // ─── 4.5: 消费任务 ──────────────────────────────────────────────────────────
    /**
     * 构建虚拟线程消费逻辑：
     * 从队列取消息 → 查找 MethodInvoker → 反序列化 payload → 反射调用 → 自动响应包装。
     * 单条消息异常不中断消费循环。
     */
    private Runnable buildConsumerTask() {
        return () -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    GameMessage msg = messageQueue.take();

                    // 毒丸消息：退出消费循环
                    if (msg == POISON_PILL) {
                        break;
                    }

                    int msgId = msg.getMsgId();
                    MsgControllerRegistry.MethodInvoker invoker = registry.find(msgId);
                    if (invoker == null) {
                        send(buildErrorResponse(msg, 404));
                        continue;
                    }

                    // 反序列化 payload
                    Message payload;
                    try {
                        Method parseFromMethod = invoker.payloadType()
                                .getMethod("parseFrom", ByteString.class);
                        payload = (Message) parseFromMethod.invoke(null, msg.getPayload());
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause();
                        if (cause instanceof InvalidProtocolBufferException) {
                            log.error("Payload 反序列化失败: sessionId={}, msgId={}", sessionId, msgId, cause);
                            send(buildErrorResponse(msg, 400));
                            continue;
                        }
                        throw e;
                    } catch (NoSuchMethodException e) {
                        log.error("Payload 类型缺少 parseFrom(ByteString) 方法: sessionId={}, msgId={}", sessionId, msgId, e);
                        send(buildErrorResponse(msg, 500));
                        continue;
                    }

                    // 反射调用 Controller 方法
                    Object result;
                    try {
                        result = invoker.method().invoke(invoker.bean(), this, payload);
                    } catch (InvocationTargetException e) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        log.error("Handler 执行异常: sessionId={}, msgId={}", sessionId, msgId, cause);
                        send(buildErrorResponse(msg, 500));
                        continue;
                    }

                    // 自动响应包装：Controller 返回 Message 子类时自动发送
                    if (result instanceof Message responseProto) {
                        int respMsgId = msg.getMsgId() + 1;
                        send(GameMessage.newBuilder()
                                .setMsgId(respMsgId)
                                .setSeq(msg.getSeq())
                                .setPayload(responseProto.toByteString())
                                .build());
                    }
                    // 返回 null 或 void：业务自行调用 session.send()

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("消费线程异常: sessionId={}", sessionId, e);
                }
            }
        };
    }

    // ─── 4.6: 工具方法 ──────────────────────────────────────────────────────────

    /**
     * 发送消息给客户端。
     */
    public void send(GameMessage message) {
        channel.writeAndFlush(message);
    }

    /**
     * 关闭连接并停止消费线程。
     */
    public void close() {
        stopAcceptingMessages();
        try {
            messageQueue.put(POISON_PILL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        channel.close();
    }

    /**
     * 绑定用户 ID 并标记为已认证。
     */
    public void bindUser(long userId) {
        this.userId = userId;
        this.authenticated = true;
    }

    /**
     * 停止接受新消息（优雅停机时调用）。
     */
    public void stopAcceptingMessages() {
        this.acceptingMessages = false;
    }

    /**
     * 等待消费线程退出：投入毒丸后 join，最多等待 timeout。
     */
    public void awaitConsumerTermination(long timeout, TimeUnit unit) {
        try {
            messageQueue.put(POISON_PILL);
            consumerThread.join(unit.toMillis(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
