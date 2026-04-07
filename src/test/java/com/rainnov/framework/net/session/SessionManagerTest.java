package com.rainnov.framework.net.session;

import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
import com.rainnov.framework.net.server.ServerMetrics;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionManagerTest {

    @Mock
    private MsgControllerRegistry registry;

    @Mock
    private Channel channel1;

    @Mock
    private Channel channel2;

    @Mock
    private ChannelFuture channelFuture;

    private ServerMetrics serverMetrics;

    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        serverMetrics = new ServerMetrics();
        sessionManager = new SessionManager(registry, serverMetrics, 30.0);
        // Stub remoteAddress for logging in createSession
        lenient().when(channel1.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 8080));
        lenient().when(channel2.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 8081));
        // Stub writeAndFlush and close for GameSession internals
        lenient().when(channel1.writeAndFlush(any())).thenReturn(channelFuture);
        lenient().when(channel2.writeAndFlush(any())).thenReturn(channelFuture);
        lenient().when(channel1.close()).thenReturn(channelFuture);
        lenient().when(channel2.close()).thenReturn(channelFuture);
    }

    @Test
    void createSession_shouldCreateAndStoreSession() {
        GameSession session = sessionManager.createSession(channel1);

        assertNotNull(session);
        assertNotNull(session.getSessionId());
        assertEquals(channel1, session.getChannel());
        assertEquals(1, sessionManager.onlineCount());
    }

    @Test
    void createSession_multipleSessions_shouldTrackAll() {
        sessionManager.createSession(channel1);
        sessionManager.createSession(channel2);

        assertEquals(2, sessionManager.onlineCount());
    }

    @Test
    void getByChannel_existingChannel_shouldReturnSession() {
        GameSession created = sessionManager.createSession(channel1);

        GameSession found = sessionManager.getByChannel(channel1);

        assertSame(created, found);
    }

    @Test
    void getByChannel_unknownChannel_shouldReturnNull() {
        assertNull(sessionManager.getByChannel(channel1));
    }

    @Test
    void removeSession_shouldRemoveAndCloseSession() {
        GameSession session = sessionManager.createSession(channel1);

        sessionManager.removeSession(channel1);

        assertEquals(0, sessionManager.onlineCount());
        assertNull(sessionManager.getByChannel(channel1));
    }

    @Test
    void removeSession_withBoundUser_shouldRemoveUserMapping() {
        GameSession session = sessionManager.createSession(channel1);
        session.bindUser(1001L);
        sessionManager.registerUserSession(session);

        assertEquals(session, sessionManager.getByUserId(1001L));

        sessionManager.removeSession(channel1);

        assertNull(sessionManager.getByUserId(1001L));
        assertEquals(0, sessionManager.onlineCount());
    }

    @Test
    void removeSession_unknownChannel_shouldDoNothing() {
        // Should not throw
        sessionManager.removeSession(channel1);
        assertEquals(0, sessionManager.onlineCount());
    }

    @Test
    void registerUserSession_shouldMapUserIdToSession() {
        GameSession session = sessionManager.createSession(channel1);
        session.bindUser(2001L);

        sessionManager.registerUserSession(session);

        assertSame(session, sessionManager.getByUserId(2001L));
    }

    @Test
    void registerUserSession_withZeroUserId_shouldNotRegister() {
        GameSession session = sessionManager.createSession(channel1);
        // userId defaults to 0, not bound

        sessionManager.registerUserSession(session);

        assertNull(sessionManager.getByUserId(0L));
    }

    @Test
    void getByUserId_unknownUser_shouldReturnNull() {
        assertNull(sessionManager.getByUserId(9999L));
    }

    @Test
    void onlineCount_shouldReflectCurrentSessions() {
        assertEquals(0, sessionManager.onlineCount());

        sessionManager.createSession(channel1);
        assertEquals(1, sessionManager.onlineCount());

        sessionManager.createSession(channel2);
        assertEquals(2, sessionManager.onlineCount());

        sessionManager.removeSession(channel1);
        assertEquals(1, sessionManager.onlineCount());
    }

    @Test
    void getAllSessions_shouldReturnAllActiveSessions() {
        assertTrue(sessionManager.getAllSessions().isEmpty());

        GameSession s1 = sessionManager.createSession(channel1);
        GameSession s2 = sessionManager.createSession(channel2);

        var allSessions = sessionManager.getAllSessions();
        assertEquals(2, allSessions.size());
        assertTrue(allSessions.contains(s1));
        assertTrue(allSessions.contains(s2));
    }
}
