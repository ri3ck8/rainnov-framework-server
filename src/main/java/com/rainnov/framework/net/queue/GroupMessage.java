package com.rainnov.framework.net.queue;

import com.rainnov.framework.net.session.GameSession;

import com.rainnov.framework.proto.GameMessageProto.GameMessage;

/**
 * 共享队列消息包装，携带原始 GameMessage 和发送方 Session。
 * 由 SharedQueueManager 和 DistributedQueueManager 共用。
 */
public record GroupMessage(GameSession session, GameMessage message) {}
