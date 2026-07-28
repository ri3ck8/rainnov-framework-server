package com.rainnov.framework.net.server;

import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * MessageDispatcher 单元测试：
 * 验证路由逻辑、未认证拦截、停机状态、限流丢弃。
 */
@ExtendWith(MockitoExtension.class)
class MessageDispatcherTest {

    @Mock private SessionManager sessionManager;
    @Mock private MsgControllerRegistry msgControllerRegistry;
    @Mock private ServerMetrics serverMetrics;
    @Mock private ChannelHandlerContext ctx;
    @Mock private Channel channel;
    @Mock private ChannelFuture channelFuture;

    private MessageDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        dispatcher = new MessageDispatcher(sessionManager, msgControllerRegistry, serverMetrics);

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
        verify(session, never()).enqueue(any());
    }

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

    @Test
    @DisplayName("Authenticated session → enqueue to user queue")
    void authenticatedSession_enqueuesToSession() throws Exception {
        GameSession session = createMockSession(true);
        when(sessionManager.getByChannel(channel)).thenReturn(session);

        MsgControllerRegistry.MethodInvoker invoker = mock(MsgControllerRegistry.MethodInvoker.class);
        when(invoker.requireAuth()).thenReturn(true);
        when(msgControllerRegistry.find(1001)).thenReturn(invoker);

        GameMessage msg = buildMsg(1001);
        dispatcher.channelRead0(ctx, msg);

        verify(session).enqueue(msg);
    }

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
