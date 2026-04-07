package com.rainnov.framework.net.session;

import com.rainnov.framework.net.dispatch.MsgControllerRegistry;
import com.rainnov.framework.net.server.ServerMetrics;
import com.rainnov.framework.proto.GameMessageProto.GameMessage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GameSession 单元测试：
 * 消息入队/消费、自动响应包装、异常隔离、POISON_PILL 退出。
 */
@ExtendWith(MockitoExtension.class)
class GameSessionTest {

    @Mock private Channel channel;
    @Mock private MsgControllerRegistry registry;
    @Mock private ChannelFuture channelFuture;

    private ServerMetrics serverMetrics;
    private GameSession session;

    @BeforeEach
    void setUp() {
        serverMetrics = new ServerMetrics();
        lenient().when(channel.writeAndFlush(any())).thenReturn(channelFuture);
        lenient().when(channel.close()).thenReturn(channelFuture);
        session = new GameSession(channel, registry, 1000.0, serverMetrics);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    // ─── 14.4: enqueue when acceptingMessages=true → message in queue ───────────

    @Test
    @DisplayName("enqueue when acceptingMessages=true → message enters queue and is consumed")
    void enqueue_acceptingMessages_messageConsumed() throws Exception {
        // Registry returns null invoker → consumer sends error response (404)
        when(registry.find(anyInt())).thenReturn(null);

        GameMessage msg = GameMessage.newBuilder().setMsgId(1001).setSeq(1).build();
        session.enqueue(msg);

        // Wait for consumer thread to process
        Thread.sleep(200);

        // Verify the consumer processed the message (sent error response since no invoker)
        verify(channel, atLeastOnce()).writeAndFlush(any(GameMessage.class));
    }

    // ─── 14.4: enqueue when acceptingMessages=false → silently dropped ──────────

    @Test
    @DisplayName("enqueue when acceptingMessages=false → message silently dropped")
    void enqueue_notAccepting_messageDropped() throws Exception {
        session.stopAcceptingMessages();

        GameMessage msg = GameMessage.newBuilder().setMsgId(1001).setSeq(1).build();
        session.enqueue(msg);

        Thread.sleep(100);

        // No message should be processed (no writeAndFlush for this message)
        // The consumer thread is still running but queue should be empty
        verify(channel, never()).writeAndFlush(any(GameMessage.class));
    }

    // ─── 14.4: enqueue when queue full → dropped, WARN logged, metrics updated ──

    @Test
    @DisplayName("enqueue when queue full → message dropped, serverMetrics.messageDropped() called")
    void enqueue_queueFull_messageDropped() throws Exception {
        // Stop accepting first, then fill queue, then re-enable to test offer failure
        // Actually, let's just fill the queue (capacity 256)
        // We need to prevent the consumer from draining the queue
        // Use a registry that blocks on find() to slow down consumption
        session.close(); // close the default session

        // Create a session with a registry that blocks
        MsgControllerRegistry blockingRegistry = mock(MsgControllerRegistry.class);
        // Make find() block to prevent consumption
        when(blockingRegistry.find(anyInt())).thenAnswer(invocation -> {
            Thread.sleep(10_000); // block for a long time
            return null;
        });

        session = new GameSession(channel, blockingRegistry, 1000.0, serverMetrics);

        // Enqueue one message to block the consumer
        session.enqueue(GameMessage.newBuilder().setMsgId(1).setSeq(0).build());
        Thread.sleep(50); // let consumer pick it up and block

        // Now fill the queue (capacity 256)
        for (int i = 0; i < 256; i++) {
            session.enqueue(GameMessage.newBuilder().setMsgId(1001).setSeq(i + 1).build());
        }

        // Next enqueue should be dropped
        long droppedBefore = serverMetrics.getMessagesDropped();
        session.enqueue(GameMessage.newBuilder().setMsgId(1001).setSeq(999).build());

        assertEquals(droppedBefore + 1, serverMetrics.getMessagesDropped());
    }

    // ─── 14.4: POISON_PILL causes consumer thread to exit ───────────────────────

    @Test
    @DisplayName("close() stops accepting and sends POISON_PILL, consumer thread exits")
    void close_stopsConsumerThread() throws Exception {
        session.close();

        // Give the consumer thread time to exit
        Thread.sleep(200);

        // After close, enqueue should be silently dropped
        GameMessage msg = GameMessage.newBuilder().setMsgId(1001).setSeq(1).build();
        session.enqueue(msg);

        Thread.sleep(100);
        // No processing should happen
        verify(channel, never()).writeAndFlush(any(GameMessage.class));

        session = null; // prevent double-close in tearDown
    }

    // ─── 14.4: awaitConsumerTermination exits cleanly ───────────────────────────

    @Test
    @DisplayName("awaitConsumerTermination sends POISON_PILL and waits for thread exit")
    void awaitConsumerTermination_exitsCleanly() {
        session.awaitConsumerTermination(5, TimeUnit.SECONDS);

        // After termination, the session should no longer process messages
        session.enqueue(GameMessage.newBuilder().setMsgId(1001).setSeq(1).build());

        // Give a moment
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        // No processing
        verify(channel, never()).writeAndFlush(any(GameMessage.class));

        session = null; // consumer already terminated, close in tearDown would still work
    }
}
