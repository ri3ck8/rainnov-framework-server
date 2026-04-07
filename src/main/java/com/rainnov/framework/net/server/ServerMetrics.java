package com.rainnov.framework.net.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 收集并定期打印服务器关键运行指标。
 * 使用 AtomicLong 保证线程安全的计数器更新。
 */
@Slf4j
@Component
public class ServerMetrics {

    private final AtomicLong onlineConnections = new AtomicLong();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesDropped = new AtomicLong();

    /**
     * 连接建立时调用：在线连接数 +1，累计连接数 +1。
     */
    public void connectionOpened() {
        onlineConnections.incrementAndGet();
        totalConnections.incrementAndGet();
    }

    /**
     * 连接断开时调用：在线连接数 -1。
     */
    public void connectionClosed() {
        onlineConnections.decrementAndGet();
    }

    /**
     * 消息成功接收时调用：累计收到消息数 +1。
     */
    public void messageReceived() {
        messagesReceived.incrementAndGet();
    }

    /**
     * 消息被丢弃时调用（队列满或限流）：累计丢弃消息数 +1。
     */
    public void messageDropped() {
        messagesDropped.incrementAndGet();
    }

    // ─── 12.2: 定期打印指标日志 ─────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000)
    public void logMetrics() {
        log.info("[ServerMetrics] online={}, total={}, received={}, dropped={}",
                onlineConnections.get(), totalConnections.get(),
                messagesReceived.get(), messagesDropped.get());
    }

    // ─── Getters ────────────────────────────────────────────────────────────────

    public long getOnlineConnections() {
        return onlineConnections.get();
    }

    public long getTotalConnections() {
        return totalConnections.get();
    }

    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    public long getMessagesDropped() {
        return messagesDropped.get();
    }
}
