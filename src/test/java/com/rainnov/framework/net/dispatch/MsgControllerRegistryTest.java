package com.rainnov.framework.net.dispatch;

import com.rainnov.framework.net.queue.GroupType;
import com.google.protobuf.Message;
import com.rainnov.framework.net.session.GameSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MsgControllerRegistry 单元测试：
 * 验证注解扫描、签名校验、重复 msgId 检测、命名一致性校验、find/registeredMsgIds。
 */
class MsgControllerRegistryTest {

    // ─── 辅助：构建 mock ApplicationContext ──────────────────────────────────────

    private MsgControllerRegistry buildRegistry(Map<String, Object> beans) throws Exception {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansWithAnnotation(MsgController.class)).thenReturn(beans);

        MsgControllerRegistry registry = new MsgControllerRegistry();
        registry.setApplicationContext(ctx);
        return registry;
    }

    // ─── 6.1 & 6.6: MethodInvoker record + find + registeredMsgIds ──────────────

    @Test
    @DisplayName("6.1 & 6.6: find() returns null for unregistered msgId; registeredMsgIds() is empty initially")
    void findReturnsNullAndRegisteredMsgIdsEmptyInitially() throws Exception {
        MsgControllerRegistry registry = buildRegistry(Map.of());
        registry.afterPropertiesSet();

        assertNull(registry.find(9999));
        assertTrue(registry.registeredMsgIds().isEmpty());
    }

    // ─── 6.2: afterPropertiesSet scans @MsgController beans ─────────────────────

    @Test
    @DisplayName("6.2: afterPropertiesSet registers @MsgMapping methods from @MsgController beans")
    void afterPropertiesSetRegistersValidMethods() throws Exception {
        MsgControllerRegistry registry = buildRegistry(
                Map.of("validController", new ValidController()));
        registry.afterPropertiesSet();

        assertNotNull(registry.find(1001));
        assertEquals(Set.of(1001), registry.registeredMsgIds());

        MsgControllerRegistry.MethodInvoker invoker = registry.find(1001);
        assertEquals(GroupType.USER, invoker.groupType());
        assertTrue(invoker.requireAuth());
    }

    // ─── 6.3: Method signature validation ───────────────────────────────────────

    @Test
    @DisplayName("6.3: Fail-Fast when first param is not GameSession")
    void failFastWhenFirstParamNotGameSession() throws Exception {
        MsgControllerRegistry registry = buildRegistry(
                Map.of("bad", new BadFirstParamController()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                registry::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("GameSession"));
    }

    @Test
    @DisplayName("6.3: Fail-Fast when second param is not Message subclass")
    void failFastWhenSecondParamNotMessage() throws Exception {
        MsgControllerRegistry registry = buildRegistry(
                Map.of("bad", new BadSecondParamController()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                registry::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("Message"));
    }

    @Test
    @DisplayName("6.3: Fail-Fast when method has wrong number of params")
    void failFastWhenWrongParamCount() throws Exception {
        MsgControllerRegistry registry = buildRegistry(
                Map.of("bad", new WrongParamCountController()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                registry::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("GameSession"));
    }

    // ─── 6.4: Duplicate msgId detection ─────────────────────────────────────────

    @Test
    @DisplayName("6.4: Fail-Fast when duplicate msgId is registered")
    void failFastOnDuplicateMsgId() throws Exception {
        MsgControllerRegistry registry = buildRegistry(
                Map.of("dup", new DuplicateMsgIdController()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                registry::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("重复注册"));
    }

    // ─── 6.5: @MsgMapping value vs param class name prefix consistency ──────────

    @Test
    @DisplayName("6.5: Fail-Fast when @MsgMapping value mismatches C{msgId}_ prefix in param class name")
    void failFastOnMsgIdMismatchWithClassName() throws Exception {
        MsgControllerRegistry registry = buildRegistry(
                Map.of("mismatch", new MismatchMsgIdController()));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                registry::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("不一致"));
    }

    @Test
    @DisplayName("6.5: No error when param class name does not follow C{msgId}_ pattern")
    void noErrorWhenParamClassNameDoesNotMatchPattern() throws Exception {
        // ValidController uses Message.class (no C{msgId}_ prefix) — should pass
        MsgControllerRegistry registry = buildRegistry(
                Map.of("valid", new ValidController()));
        assertDoesNotThrow(registry::afterPropertiesSet);
    }

    // ─── 6.6: find() and registeredMsgIds() after registration ──────────────────

    @Test
    @DisplayName("6.6: find() returns correct MethodInvoker; registeredMsgIds() returns all registered ids")
    void findAndRegisteredMsgIdsAfterRegistration() throws Exception {
        MsgControllerRegistry registry = buildRegistry(
                Map.of("multi", new MultiMethodController()));
        registry.afterPropertiesSet();

        assertNotNull(registry.find(2001));
        assertNotNull(registry.find(2002));
        assertNull(registry.find(9999));
        assertEquals(Set.of(2001, 2002), registry.registeredMsgIds());
    }

    @Test
    @DisplayName("6.2: @MsgMapping attributes (groupBy, requireAuth) are captured correctly")
    void msgMappingAttributesCaptured() throws Exception {
        MsgControllerRegistry registry = buildRegistry(
                Map.of("attrs", new AttributeController()));
        registry.afterPropertiesSet();

        MsgControllerRegistry.MethodInvoker invoker = registry.find(3001);
        assertNotNull(invoker);
        assertEquals(GroupType.TEAM, invoker.groupType());
        assertFalse(invoker.requireAuth());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test controller stubs (inner classes)
    // ═══════════════════════════════════════════════════════════════════════════

    @MsgController
    static class ValidController {
        @MsgMapping(value = 1001)
        public void handle(GameSession session, Message msg) {}
    }

    @MsgController
    static class BadFirstParamController {
        @MsgMapping(value = 1001)
        public void handle(String notSession, Message msg) {}
    }

    @MsgController
    static class BadSecondParamController {
        @MsgMapping(value = 1001)
        public void handle(GameSession session, String notMessage) {}
    }

    @MsgController
    static class WrongParamCountController {
        @MsgMapping(value = 1001)
        public void handle(Message msg) {}
    }

    @MsgController
    static class DuplicateMsgIdController {
        @MsgMapping(value = 5001)
        public void handleA(GameSession session, Message msg) {}

        @MsgMapping(value = 5001)
        public void handleB(GameSession session, Message msg) {}
    }

    /**
     * Simulates a mismatch: @MsgMapping(2001) but param type name starts with C9999_
     * We use a custom abstract class to simulate the naming pattern.
     */
    @MsgController
    static class MismatchMsgIdController {
        @MsgMapping(value = 2001)
        public void handle(GameSession session, C9999_FakeMsg msg) {}
    }

    /** Fake Message subclass whose name starts with C9999_ to trigger mismatch detection */
    static abstract class C9999_FakeMsg extends com.google.protobuf.GeneratedMessage {}

    @MsgController
    static class MultiMethodController {
        @MsgMapping(value = 2001)
        public void handleA(GameSession session, Message msg) {}

        @MsgMapping(value = 2002)
        public void handleB(GameSession session, Message msg) {}
    }

    @MsgController
    static class AttributeController {
        @MsgMapping(value = 3001, groupBy = GroupType.TEAM, requireAuth = false)
        public void handle(GameSession session, Message msg) {}
    }
}
