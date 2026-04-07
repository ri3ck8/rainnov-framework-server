package com.rainnov.framework.net.server;

import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
import com.rainnov.framework.net.queue.*;
import com.rainnov.framework.net.session.GameSession;
import com.rainnov.framework.net.session.SessionManager;
import com.rainnov.framework.proto.GameMessageProto.GameMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MessageDispatcher 单元测试：
 * 验证路由逻辑、未认证拦截、停机状态、限流丢弃、groupType 路由。
 */
@ExtendWith(MockitoExtension.class)
class MessageDispatcherTest {

    @Mock private SessionManager sessionManager;
    @Mock private MsgControllerRegistry msgControllerRegistry;
    @Mock private SharedQueueManager sharedQueueManager;
    @Mock private ServerMetrics serverMetrics;
    @Mock private GroupKeyResolver groupKeyResolver;
    @Mock private ChannelHandlerContext ctx;
    @Mock private Channel channel;
    @Mock private ChannelFuture channelFuture;

    private MessageDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        dispatcher = new MessageDispatcher(sessionManager, msgControllerRegistry, sharedQueueManager, serverMetrics);
        // Inject groupKeyResolver via reflection (it's @Autowired(required=false))
        Field resolverField = MessageDispatcher.class.getDeclaredField("groupKeyResolver");
        resolverField.setAccessible(true);
        resolverField.set(dispatcher, groupKeyResolver);

        lenient().when(ctx.channel()).thenReturn(channel);
        lenient().when(channel.writeAndFlush(any())).thenReturn(channelFuture);
    }

    private GameSession createMockSession(boolean authenticated) {
        GameSession session = mock(GameSession.class);
        lenient().when(session.isAuthenticated()).thenReturn(authenticated);
        lenient().when(session.tryAcquireRateLimit()).thenReturn(true);
        lenient().when(session.getSessionId()).thenReturn("test-session");
        return session;
    }

    private GameMessage buildMsg(int msgId) {
        return GameMessage.newBuilder().setMsgId(msgId).setSeq(1).build();
    }

    // ─── 14.2: 未认证 + requireAuth=true → error_code=401 ──────────────────────

    @Test
    @DisplayName("Unauthenticated session + requireAuth=true → sends error_code=401")
    void unauthenticatedWithRequireAuth_sendsError401() throws Exception {
        GameSession session = createMockSession(false);
        when(sessionManager.getByChannel(channel)).thenReturn(session);

        MsgControllerRegistry.MethodInvoker invoker = mock(MsgControllerRegistry.MethodInvoker.class);
        when(invoker.requireAuth()).thenReturn(true);
        when(msgControllerRegistry.find(1001)).thenReturn(invoker);

        dispatcher.channelRead0(ctx, buildMsg(1001));

        ArgumentCaptor<GameMessage> captor = ArgumentCaptor.forClass(GameMessage.class);
        verify(session).send(captor.capture());
        assertEquals(401, captor.getValue().getErrorCode());
        // Should NOT enqueue
        verify(session, never()).enqueue(any());
    }

    // ─── 14.2: 未认证 + unknown msgId → error_code=401 ─────────────────────────

    @Test
    @DisplayName("Unauthenticated session + unknown msgId → sends error_code=401")
    void unauthenticatedWithUnknownMsgId_sendsError401() throws Exception {
        GameSession session = createMockSession(false);
        when(sessionManager.getByChannel(channel)).thenReturn(session);
        when(msgControllerRegistry.find(9999)).thenReturn(null);

        dispatcher.channelRead0(ctx, buildMsg(9999));

        ArgumentCaptor<GameMessage> captor = ArgumentCaptor.forClass(GameMessage.class);
        verify(session).send(captor.capture());
        assertEquals(401, captor.getValue().getErrorCode());
    }

    // ─── 14.2: 已认证 + USER groupType → enqueue to session ────────────────────

    @Test
    @DisplayName("Authenticated session + USER groupType → enqueue to session")
    void authenticatedUserGroupType_enqueuesToSession() throws Exception {
        GameSession session = createMockSession(true);
        when(sessionManager.getByChannel(channel)).thenReturn(session);

        MsgControllerRegistry.MethodInvoker invoker = mock(MsgControllerRegistry.MethodInvoker.class);
        when(invoker.requireAuth()).thenReturn(true);
        when(invoker.groupType()).thenReturn(GroupType.USER);
        when(msgControllerRegistry.find(1001)).thenReturn(invoker);

        GameMessage msg = buildMsg(1001);
        dispatcher.channelRead0(ctx, msg);

        verify(session).enqueue(msg);
    }

    // ─── 14.2: 已认证 + TEAM groupType + valid groupKey → SharedQueue ──────────

    @Test
    @DisplayName("Authenticated session + TEAM groupType + valid groupKey → enqueue to SharedQueue")
    void authenticatedTeamGroupType_enqueuesToSharedQueue() throws Exception {
        GameSession session = createMockSession(true);
        when(sessionManager.getByChannel(channel)).thenReturn(session);

        MsgControllerRegistry.MethodInvoker invoker = mock(MsgControllerRegistry.MethodInvoker.class);
        when(invoker.requireAuth()).thenReturn(true);
        when(invoker.groupType()).thenReturn(GroupType.TEAM);
        when(msgControllerRegistry.find(2001)).thenReturn(invoker);

        when(groupKeyResolver.resolve(session, GroupType.TEAM)).thenReturn("team:123");
        SharedQueueManager.SharedQueue sharedQueue = mock(SharedQueueManager.SharedQueue.class);
        when(sharedQueueManager.getOrCreate("team:123")).thenReturn(sharedQueue);

        GameMessage msg = buildMsg(2001);
        dispatcher.channelRead0(ctx, msg);

        verify(sharedQueue).enqueue(any(GroupMessage.class));
        verify(session, never()).enqueue(any());
    }

    // ─── 14.2: shuttingDown=true → message not enqueued ─────────────────────────

    @Test
    @DisplayName("shuttingDown=true → message not enqueued")
    void shuttingDown_messageNotEnqueued() throws Exception {
        GameSession session = createMockSession(true);
        when(sessionManager.getByChannel(channel)).thenReturn(session);

        dispatcher.setShuttingDown(true);

        dispatcher.channelRead0(ctx, buildMsg(1001));

        verify(session, never()).enqueue(any());
        verify(session, never()).send(any());
    }

    // ─── 14.2: rate limit exceeded → message dropped ────────────────────────────

    @Test
    @DisplayName("Rate limit exceeded → message dropped, serverMetrics.messageDropped() called")
    void rateLimitExceeded_messageDropped() throws Exception {
        GameSession session = createMockSession(true);
        when(session.tryAcquireRateLimit()).thenReturn(false);
        when(sessionManager.getByChannel(channel)).thenReturn(session);

        dispatcher.channelRead0(ctx, buildMsg(1001));

        verify(serverMetrics).messageDropped();
        verify(session, never()).enqueue(any());
    }

}
