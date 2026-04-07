package com.rainnov.framework.net.queue;

import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
import com.rainnov.framework.net.server.ServerMetrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SharedQueueManager 单元测试：
 * getOrCreate 幂等性、不同 groupKey 返回不同实例、空闲超时销毁。
 */
@ExtendWith(MockitoExtension.class)
class SharedQueueManagerTest {

    @Mock
    private MsgControllerRegistry registry;

    private SharedQueueManager manager;

    @BeforeEach
    void setUp() {
        manager = new SharedQueueManager(registry);
    }

    // ─── 14.5: getOrCreate returns same instance for same groupKey (idempotent) ─

    @Test
    @DisplayName("getOrCreate returns same instance for same groupKey (idempotent)")
    void getOrCreate_sameGroupKey_returnsSameInstance() {
        SharedQueueManager.SharedQueue q1 = manager.getOrCreate("team:123");
        SharedQueueManager.SharedQueue q2 = manager.getOrCreate("team:123");

        assertSame(q1, q2);
        assertEquals(1, manager.activeQueueCount());
    }

    // ─── 14.5: getOrCreate returns different instances for different groupKeys ───

    @Test
    @DisplayName("getOrCreate returns different instances for different groupKeys")
    void getOrCreate_differentGroupKeys_returnsDifferentInstances() {
        SharedQueueManager.SharedQueue q1 = manager.getOrCreate("team:123");
        SharedQueueManager.SharedQueue q2 = manager.getOrCreate("team:456");

        assertNotSame(q1, q2);
        assertEquals(2, manager.activeQueueCount());
    }

    // ─── 14.5: cleanupIdleQueues removes idle+empty queues ──────────────────────

    @Test
    @DisplayName("cleanupIdleQueues removes idle and empty queues")
    void cleanupIdleQueues_removesIdleEmptyQueues() throws Exception {
        SharedQueueManager.SharedQueue q = manager.getOrCreate("team:idle");
        assertEquals(1, manager.activeQueueCount());

        // Use reflection to set lastActiveTime to a time well in the past (> 5 min ago)
        java.lang.reflect.Field lastActiveField = SharedQueueManager.SharedQueue.class.getDeclaredField("lastActiveTime");
        lastActiveField.setAccessible(true);
        lastActiveField.setLong(q, System.currentTimeMillis() - 6 * 60 * 1000L); // 6 minutes ago

        // Trigger cleanup
        manager.cleanupIdleQueues();

        assertEquals(0, manager.activeQueueCount());
    }

    // ─── 14.5: cleanupIdleQueues does NOT remove active queues ──────────────────

    @Test
    @DisplayName("cleanupIdleQueues does NOT remove recently active queues")
    void cleanupIdleQueues_doesNotRemoveActiveQueues() {
        manager.getOrCreate("team:active");
        assertEquals(1, manager.activeQueueCount());

        // lastActiveTime is set to now in constructor, so it's not idle
        manager.cleanupIdleQueues();

        assertEquals(1, manager.activeQueueCount());
    }
}
