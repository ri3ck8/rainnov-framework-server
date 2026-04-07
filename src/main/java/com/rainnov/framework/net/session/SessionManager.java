package com.rainnov.framework.net.session;

import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
import com.rainnov.framework.net.server.ServerMetrics;

import io.netty.channel.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理所有在线 GameSession，支持按 Channel 和 userId 查找。
 */
@Slf4j
@Component
public class SessionManager {

    private final ConcurrentHashMap<Channel, GameSession> channelSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, GameSession> userSessions = new ConcurrentHashMap<>();

    private final MsgControllerRegistry msgControllerRegistry;
    private final ServerMetrics serverMetrics;
    private final double rateLimitPerSecond;

    public SessionManager(MsgControllerRegistry msgControllerRegistry,
                          ServerMetrics serverMetrics,
                          @Value("${game.server.rate-limit.per-second:30}") double rateLimitPerSecond) {
        this.msgControllerRegistry = msgControllerRegistry;
        this.serverMetrics = serverMetrics;
        this.rateLimitPerSecond = rateLimitPerSecond;
    }

    /**
     * 创建新的 GameSession 并与 Channel 关联。
     */
    public GameSession createSession(Channel channel) {
        GameSession session = new GameSession(channel, msgControllerRegistry, rateLimitPerSecond, serverMetrics);
        channelSessions.put(channel, session);
        serverMetrics.connectionOpened();
        log.info("Session 创建: sessionId={}, channel={}", session.getSessionId(), channel.remoteAddress());
        return session;
    }

    /**
     * 移除 Channel 对应的 GameSession。
     * 同时清理 userId 映射（如果已绑定），并关闭 Session。
     */
    public void removeSession(Channel channel) {
        GameSession session = channelSessions.remove(channel);
        if (session != null) {
            if (session.getUserId() != 0) {
                userSessions.remove(session.getUserId());
            }
            session.close();
            serverMetrics.connectionClosed();
            log.info("Session 移除: sessionId={}, userId={}", session.getSessionId(), session.getUserId());
        }
    }

    /**
     * 注册用户 Session 映射（业务层在 bindUser 后调用）。
     */
    public void registerUserSession(GameSession session) {
        if (session.getUserId() != 0) {
            userSessions.put(session.getUserId(), session);
            log.info("用户 Session 注册: userId={}, sessionId={}", session.getUserId(), session.getSessionId());
        }
    }

    /**
     * 通过 Channel 查找 GameSession。
     */
    public GameSession getByChannel(Channel channel) {
        return channelSessions.get(channel);
    }

    /**
     * 通过 userId 查找 GameSession。
     */
    public GameSession getByUserId(long userId) {
        return userSessions.get(userId);
    }

    /**
     * 返回当前在线连接数。
     */
    public int onlineCount() {
        return channelSessions.size();
    }

    /**
     * 返回所有在线 Session。
     */
    public Collection<GameSession> getAllSessions() {
        return channelSessions.values();
    }
}
