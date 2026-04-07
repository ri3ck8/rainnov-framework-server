package com.rainnov.framework.net.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
import com.rainnov.framework.proto.GameMessageProto.GameMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨进程分布式队列管理器。
 * 使用 Redis List 作为消息队列，配合分布式锁保证同一 groupKey 同时只有一个节点在消费。
 * 仅 GroupType.TEAM_DISTRIBUTED / GUILD_DISTRIBUTED 的消息使用此路径。
 *
 * Redis 键规范：
 * - game:queue:{groupKey} — List（RPUSH 入队，BLPOP 消费）
 * - game:lock:{groupKey} — String（SET NX PX 5000 分布式锁）
 *
 * Redis 不可用时自动降级到本地 SharedQueueManager。
 */
@Slf4j
@Component
@ConditionalOnBean(StringRedisTemplate.class)
public class DistributedQueueManager {

    private static final String QUEUE_KEY_PREFIX = "game:queue:";
    private static final String LOCK_KEY_PREFIX = "game:lock:";
    private static final Duration LOCK_TIMEOUT = Duration.ofMillis(5000);
    private static final long BLPOP_TIMEOUT_SECONDS = 2;
    private static final int MAX_LOCK_RETRIES = 3;
    private static final long LOCK_RETRY_WAIT_MS = 500;

    private final StringRedisTemplate redisTemplate;
    private final SharedQueueManager sharedQueueManager;
    private final MsgControllerRegistry registry;
    private final ObjectMapper objectMapper;

    /** 跟踪每个 groupKey 是否已启动消费线程 */
    private final ConcurrentHashMap<String, Thread> consumerThreads = new ConcurrentHashMap<>();

    public DistributedQueueManager(StringRedisTemplate redisTemplate,
                                   SharedQueueManager sharedQueueManager,
                                   MsgControllerRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.sharedQueueManager = sharedQueueManager;
        this.registry = registry;
        this.objectMapper = new ObjectMapper();
    }


    // ─── 8.1: 序列化/反序列化 ────────────────────────────────────────────────────

    /**
     * 将 GroupMessage 转换为可序列化的 DistributedGroupMessage 并序列化为 JSON。
     */
    String serialize(GroupMessage message) throws JsonProcessingException {
        DistributedGroupMessage dto = new DistributedGroupMessage(
                message.session().getSessionId(),
                message.session().getUserId(),
                message.message().toByteArray()
        );
        return objectMapper.writeValueAsString(dto);
    }

    /**
     * 从 JSON 反序列化为 DistributedGroupMessage。
     */
    DistributedGroupMessage deserialize(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, DistributedGroupMessage.class);
    }

    // ─── 8.2: enqueue ───────────────────────────────────────────────────────────

    /**
     * 将消息序列化后 RPUSH 到 Redis List game:queue:{groupKey}。
     * Redis 不可用时自动降级到 SharedQueueManager（8.4）。
     */
    public void enqueue(String groupKey, GroupMessage message) {
        try {
            String json = serialize(message);
            redisTemplate.opsForList().rightPush(QUEUE_KEY_PREFIX + groupKey, json);
            // 确保该 groupKey 有消费线程在运行
            ensureConsumerStarted(groupKey);
        } catch (Exception e) {
            // ─── 8.4: Redis 不可用时降级 ─────────────────────────────────────────
            log.error("Redis 入队失败，降级到本地 SharedQueueManager: groupKey={}", groupKey, e);
            sharedQueueManager.getOrCreate(groupKey).enqueue(message);
        }
    }

    // ─── 8.3: startConsumer ─────────────────────────────────────────────────────

    /**
     * 确保 groupKey 对应的消费线程已启动（幂等）。
     */
    private void ensureConsumerStarted(String groupKey) {
        consumerThreads.computeIfAbsent(groupKey, key ->
                Thread.ofVirtual()
                        .name("distributed-queue-consumer-" + key)
                        .start(() -> consumeLoop(key))
        );
    }

    /**
     * 启动指定 groupKey 的消费循环。
     * 流程：SET NX PX 5000 获取锁 → BLPOP 消费 → 释放锁。
     * 锁获取失败时重试，超过最大重试次数则降级（8.5）。
     */
    public void startConsumer(String groupKey) {
        ensureConsumerStarted(groupKey);
    }

    /**
     * 消费循环核心逻辑。
     */
    private void consumeLoop(String groupKey) {
        String queueKey = QUEUE_KEY_PREFIX + groupKey;
        String lockKey = LOCK_KEY_PREFIX + groupKey;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // ─── 8.5: 分布式锁获取（含重试） ────────────────────────────────
                boolean lockAcquired = tryAcquireLockWithRetry(lockKey, groupKey);
                if (!lockAcquired) {
                    // 超过最大重试次数，本轮放弃，短暂休眠后继续下一轮
                    Thread.sleep(LOCK_RETRY_WAIT_MS);
                    continue;
                }

                try {
                    // BLPOP 消费（阻塞等待，超时后释放锁让其他节点竞争）
                    String json = redisTemplate.opsForList()
                            .leftPop(queueKey, Duration.ofSeconds(BLPOP_TIMEOUT_SECONDS));

                    if (json != null) {
                        processMessage(groupKey, json);
                    }
                } finally {
                    // 释放锁
                    releaseLock(lockKey);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // ─── 8.4: Redis 消费异常时记录 ERROR ─────────────────────────────
                log.error("分布式队列消费异常，groupKey={}", groupKey, e);
                try {
                    Thread.sleep(1000); // 避免异常风暴
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        consumerThreads.remove(groupKey);
    }

    // ─── 8.5: 分布式锁超时重试及降级 ────────────────────────────────────────────

    /**
     * 尝试获取分布式锁，最多重试 MAX_LOCK_RETRIES 次。
     * 超过最大重试次数时记录 WARN 日志并返回 false。
     */
    private boolean tryAcquireLockWithRetry(String lockKey, String groupKey) throws InterruptedException {
        for (int attempt = 1; attempt <= MAX_LOCK_RETRIES; attempt++) {
            try {
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, "locked", LOCK_TIMEOUT);
                if (Boolean.TRUE.equals(acquired)) {
                    return true;
                }
                // 锁被其他节点持有，等待后重试
                if (attempt < MAX_LOCK_RETRIES) {
                    Thread.sleep(LOCK_RETRY_WAIT_MS);
                }
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                // Redis 异常，记录 ERROR 并降级
                log.error("分布式锁获取异常，降级到本地队列: groupKey={}", groupKey, e);
                return false;
            }
        }
        // 超过最大重试次数
        log.warn("分布式锁获取超时（重试 {} 次），groupKey={}", MAX_LOCK_RETRIES, groupKey);
        return false;
    }

    /**
     * 释放分布式锁。
     */
    private void releaseLock(String lockKey) {
        try {
            redisTemplate.delete(lockKey);
        } catch (Exception e) {
            log.error("释放分布式锁失败: lockKey={}", lockKey, e);
        }
    }

    /**
     * 处理从 Redis 消费到的消息。
     * 反序列化 DistributedGroupMessage，解析 GameMessage，调用 Handler。
     * 当前版本：跨节点响应路由标记为 TODO。
     */
    private void processMessage(String groupKey, String json) {
        try {
            DistributedGroupMessage dto = deserialize(json);
            GameMessage gameMessage = GameMessage.parseFrom(dto.messageBytes());

            int msgId = gameMessage.getMsgId();
            MsgControllerRegistry.MethodInvoker invoker = registry.find(msgId);
            if (invoker == null) {
                log.warn("分布式队列消费：未找到 Handler, groupKey={}, msgId={}", groupKey, msgId);
                return;
            }

            // TODO: 跨节点场景 — 消费节点可能不持有原始 Session
            // 当前版本仅记录日志，后续需实现跨节点响应路由
            log.debug("分布式队列消费消息: groupKey={}, msgId={}, sessionId={}, userId={}",
                    groupKey, msgId, dto.sessionId(), dto.userId());

        } catch (Exception e) {
            log.error("分布式队列消息处理失败: groupKey={}", groupKey, e);
        }
    }
}
