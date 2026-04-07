package com.rainnov.framework.net.queue;

import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
import com.rainnov.framework.net.session.GameSession;
import com.rainnov.framework.net.server.ServerMetrics;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.rainnov.framework.proto.GameMessageProto.GameMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 管理进程内按 groupKey 串行消费的共享队列。
 * 每个 groupKey 对应一个独立的有界队列和专属虚拟消费线程；
 * 队列空闲超过 5 分钟且为空时自动销毁，防止内存泄漏。
 */
@Slf4j
@Component
public class SharedQueueManager {

    private static final long IDLE_TIMEOUT_MS = 5 * 60 * 1000L; // 5 分钟

    private final ConcurrentHashMap<String, SharedQueue> queueMap = new ConcurrentHashMap<>();
    private final MsgControllerRegistry registry;

    public SharedQueueManager(MsgControllerRegistry registry) {
        this.registry = registry;
    }

    // ─── 7.2: getOrCreate ────────────────────────────────────────────────────────

    /**
     * 获取或创建 groupKey 对应的共享队列（线程安全）。
     */
    public SharedQueue getOrCreate(String groupKey) {
        return queueMap.computeIfAbsent(groupKey, key -> new SharedQueue(key, registry));
    }

    // ─── 7.4: 空闲超时清理定时任务 ──────────────────────────────────────────────

    /**
     * 每 30 秒扫描所有 SharedQueue，清理空闲超时（5 分钟）且队列为空的队列。
     */
    @Scheduled(fixedDelay = 30_000)
    public void cleanupIdleQueues() {
        long now = System.currentTimeMillis();
        var iterator = queueMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, SharedQueue> entry = iterator.next();
            SharedQueue sq = entry.getValue();
            if (sq.isIdleAndEmpty(now, IDLE_TIMEOUT_MS)) {
                iterator.remove();
                sq.shutdown();
                log.info("清理空闲 SharedQueue: groupKey={}", entry.getKey());
            }
        }
    }

    /**
     * 获取当前活跃的共享队列数量（用于监控）。
     */
    public int activeQueueCount() {
        return queueMap.size();
    }

    // ─── 7.5: awaitAllQueues ─────────────────────────────────────────────────────

    /**
     * 等待所有共享队列消费完毕（优雅停机用）。
     * 向每个队列投入毒丸，然后 join 所有消费线程。
     */
    public void awaitAllQueues(long timeout, TimeUnit unit) {
        long deadlineMs = System.currentTimeMillis() + unit.toMillis(timeout);
        for (Map.Entry<String, SharedQueue> entry : queueMap.entrySet()) {
            long remaining = deadlineMs - System.currentTimeMillis();
            if (remaining <= 0) {
                log.warn("awaitAllQueues 超时，部分队列可能未消费完毕");
                break;
            }
            entry.getValue().awaitTermination(remaining);
        }
        queueMap.clear();
    }

    // ─── 7.1: SharedQueue 内部类 ─────────────────────────────────────────────────

    /**
     * 单个 groupKey 对应的共享队列。
     * 拥有容量 1024 的有界队列和一个专属虚拟消费线程。
     */
    public static class SharedQueue {

        private static final GroupMessage POISON_PILL = new GroupMessage(null, null);

        private final String groupKey;
        private final LinkedBlockingQueue<GroupMessage> queue = new LinkedBlockingQueue<>(1024);
        private final Thread consumerThread;
        private volatile long lastActiveTime;

        SharedQueue(String groupKey, MsgControllerRegistry registry) {
            this.groupKey = groupKey;
            this.lastActiveTime = System.currentTimeMillis();
            this.consumerThread = Thread.ofVirtual()
                    .name("shared-queue-consumer-" + groupKey)
                    .start(buildConsumerTask(registry));
        }

        // ─── 7.3: enqueue ───────────────────────────────────────────────────────

        /**
         * 将消息投入队列（非阻塞）。满时丢弃并记录 WARN。
         */
        public void enqueue(GroupMessage message) {
            if (!queue.offer(message)) {
                log.warn("SharedQueue 队列已满，丢弃消息: groupKey={}, msgId={}",
                        groupKey, message.message().getMsgId());
            }
        }

        /**
         * 检查队列是否空闲超时且为空。
         */
        boolean isIdleAndEmpty(long now, long idleTimeoutMs) {
            return (now - lastActiveTime > idleTimeoutMs) && queue.isEmpty();
        }

        /**
         * 中断消费线程（清理时调用）。
         */
        void shutdown() {
            consumerThread.interrupt();
        }

        /**
         * 投入毒丸并等待消费线程退出（优雅停机用）。
         */
        void awaitTermination(long timeoutMs) {
            try {
                queue.put(POISON_PILL);
                consumerThread.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * 构建虚拟线程消费逻辑：
         * 从队列取 GroupMessage → 提取 session 和 message → 查找 MethodInvoker →
         * 反序列化 payload → 反射调用 → 自动响应包装。
         * 错误码通过原始 session 发回客户端。
         */
        private Runnable buildConsumerTask(MsgControllerRegistry registry) {
            return () -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        GroupMessage groupMsg = queue.take();

                        // 毒丸消息：退出消费循环
                        if (groupMsg == POISON_PILL) {
                            break;
                        }

                        lastActiveTime = System.currentTimeMillis();

                        GameSession session = groupMsg.session();
                        GameMessage msg = groupMsg.message();
                        int msgId = msg.getMsgId();

                        MsgControllerRegistry.MethodInvoker invoker = registry.find(msgId);
                        if (invoker == null) {
                            session.send(buildErrorResponse(msg, 404));
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
                                log.error("Payload 反序列化失败: groupKey={}, msgId={}", groupKey, msgId, cause);
                                session.send(buildErrorResponse(msg, 400));
                                continue;
                            }
                            throw e;
                        } catch (NoSuchMethodException e) {
                            log.error("Payload 类型缺少 parseFrom(ByteString) 方法: groupKey={}, msgId={}", groupKey, msgId, e);
                            session.send(buildErrorResponse(msg, 500));
                            continue;
                        }

                        // 反射调用 Controller 方法
                        Object result;
                        try {
                            result = invoker.method().invoke(invoker.bean(), session, payload);
                        } catch (InvocationTargetException e) {
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            log.error("Handler 执行异常: groupKey={}, msgId={}", groupKey, msgId, cause);
                            session.send(buildErrorResponse(msg, 500));
                            continue;
                        }

                        // 自动响应包装
                        if (result instanceof Message responseProto) {
                            int respMsgId = msg.getMsgId() + 1;
                            session.send(GameMessage.newBuilder()
                                    .setMsgId(respMsgId)
                                    .setSeq(msg.getSeq())
                                    .setPayload(responseProto.toByteString())
                                    .build());
                        }

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.error("SharedQueue 消费线程异常: groupKey={}", groupKey, e);
                    }
                }
            };
        }

        /**
         * 构建错误响应 GameMessage。
         */
        private static GameMessage buildErrorResponse(GameMessage request, int errorCode) {
            return GameMessage.newBuilder()
                    .setMsgId(request.getMsgId())
                    .setSeq(request.getSeq())
                    .setErrorCode(errorCode)
                    .build();
        }
    }
}
