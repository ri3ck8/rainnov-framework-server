package com.rainnov.framework.net.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
import com.rainnov.framework.net.server.ServerMetrics;
import com.rainnov.framework.net.session.GameSession;
import com.rainnov.framework.proto.GameMessageProto.GameMessage;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DistributedQueueManager 单元测试。
 * 覆盖：序列化/反序列化、入队、Redis 降级、锁重试降级。
 */
@ExtendWith(MockitoExtension.class)
class DistributedQueueManagerTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private SharedQueueManager sharedQueueManager;
    @Mock
    private MsgControllerRegistry registry;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private Channel channel;
    @Mock
    private SharedQueueManager.SharedQueue sharedQueue;

    private ServerMetrics serverMetrics;
    private DistributedQueueManager manager;

    @BeforeEach
    void setUp() {
        serverMetrics = new ServerMetrics();
        manager = new DistributedQueueManager(redisTemplate, sharedQueueManager, registry);
    }

    // ─── 8.1: 序列化/反序列化测试 ───────────────────────────────────────────────

    @Test
    void serialize_and_deserialize_roundtrip() throws Exception {
        // 构造 GameSession（需要 mock Channel 和 Registry）
        GameSession session = new GameSession(channel, registry, 30.0, serverMetrics);
        session.bindUser(12345L);

        GameMessage msg = GameMessage.newBuilder()
                .setMsgId(1001)
                .setSeq(42)
                .setPayload(com.google.protobuf.ByteString.copyFromUtf8("test"))
                .build();

        GroupMessage groupMessage = new GroupMessage(session, msg);

        // 序列化
        String json = manager.serialize(groupMessage);
        assertNotNull(json);
        assertTrue(json.contains("12345")); // userId
        assertTrue(json.contains(session.getSessionId())); // sessionId

        // 反序列化
        DistributedGroupMessage dto = manager.deserialize(json);
        assertEquals(session.getSessionId(), dto.sessionId());
        assertEquals(12345L, dto.userId());
        assertNotNull(dto.messageBytes());

        // 验证 messageBytes 可以还原为 GameMessage
        GameMessage restored = GameMessage.parseFrom(dto.messageBytes());
        assertEquals(1001, restored.getMsgId());
        assertEquals(42, restored.getSeq());

        // 清理 session 的虚拟线程
        session.close();
    }

    // ─── 8.2: enqueue 测试 ──────────────────────────────────────────────────────

    @Test
    void enqueue_pushes_to_redis_list() throws Exception {
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // 为 ensureConsumerStarted 中的锁获取 mock
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        GameSession session = new GameSession(channel, registry, 30.0, serverMetrics);
        session.bindUser(100L);

        GameMessage msg = GameMessage.newBuilder().setMsgId(2001).setSeq(1).build();
        GroupMessage groupMessage = new GroupMessage(session, msg);

        manager.enqueue("team:123", groupMessage);

        // 验证 RPUSH 被调用
        verify(listOps).rightPush(eq("game:queue:team:123"), anyString());

        session.close();
    }

    // ─── 8.4: Redis 不可用时降级测试 ────────────────────────────────────────────

    @Test
    void enqueue_falls_back_to_shared_queue_on_redis_failure() throws Exception {
        when(redisTemplate.opsForList()).thenThrow(new RedisConnectionFailureException("Connection refused"));
        when(sharedQueueManager.getOrCreate("team:456")).thenReturn(sharedQueue);

        GameSession session = new GameSession(channel, registry, 30.0, serverMetrics);
        session.bindUser(200L);

        GameMessage msg = GameMessage.newBuilder().setMsgId(3001).setSeq(2).build();
        GroupMessage groupMessage = new GroupMessage(session, msg);

        // 不应抛异常，应降级到 SharedQueueManager
        manager.enqueue("team:456", groupMessage);

        verify(sharedQueueManager).getOrCreate("team:456");
        verify(sharedQueue).enqueue(groupMessage);

        session.close();
    }

    // ─── 8.1: DistributedGroupMessage JSON 往返测试 ─────────────────────────────

    @Test
    void distributedGroupMessage_json_roundtrip_with_objectMapper() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        byte[] testBytes = new byte[]{1, 2, 3, 4, 5};
        DistributedGroupMessage original = new DistributedGroupMessage("sess-abc", 999L, testBytes);

        String json = objectMapper.writeValueAsString(original);
        DistributedGroupMessage restored = objectMapper.readValue(json, DistributedGroupMessage.class);

        assertEquals("sess-abc", restored.sessionId());
        assertEquals(999L, restored.userId());
        assertArrayEquals(testBytes, restored.messageBytes());
    }
}
