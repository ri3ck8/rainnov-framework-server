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

    @Test
    @DisplayName("enqueue when acceptingMessages=true → message enters queue and is consumed")
    void enqueue_acceptingMessages_messageConsumed() throws Exception {
        // registry 返回 null invoker → 消费线程回发 404 错误响应
        when(registry.find(anyInt())).thenReturn(null);

        GameMessage msg = GameMessage.newBuilder().setMsgId(1001).setSeq(1).build();
        session.enqueue(msg);

        // 等待消费线程处理
        Thread.sleep(200);

        verify(channel, atLeastOnce()).writeAndFlush(any(GameMessage.class));
    }

    @Test
    @DisplayName("enqueue when acceptingMessages=false → message silently dropped")
    void enqueue_notAccepting_messageDropped() throws Exception {
        session.stopAcceptingMessages();

        GameMessage msg = GameMessage.newBuilder().setMsgId(1001).setSeq(1).build();
        session.enqueue(msg);

        Thread.sleep(100);

        // 消费线程仍在运行，但队列为空，不应有任何消息被处理
        verify(channel, never()).writeAndFlush(any(GameMessage.class));
    }

    @Test
    @DisplayName("enqueue when queue full → message dropped, serverMetrics.messageDropped() called")
    void enqueue_queueFull_messageDropped() throws Exception {
        // 用一个在 find() 上阻塞的 registry 拖住消费线程，才能把队列填满
        session.close();

        MsgControllerRegistry blockingRegistry = mock(MsgControllerRegistry.class);
        when(blockingRegistry.find(anyInt())).thenAnswer(invocation -> {
            Thread.sleep(10_000); // 长时间阻塞
            return null;
        });

        session = new GameSession(channel, blockingRegistry, 1000.0, serverMetrics);

        // 先入队一条消息把消费线程卡住
        session.enqueue(GameMessage.newBuilder().setMsgId(1).setSeq(0).build());
        Thread.sleep(50); // 等消费线程取走并阻塞

        // 填满队列（容量 256）
        for (int i = 0; i < 256; i++) {
            session.enqueue(GameMessage.newBuilder().setMsgId(1001).setSeq(i + 1).build());
        }

        // 再入队的消息应被丢弃
        long droppedBefore = serverMetrics.getMessagesDropped();
        session.enqueue(GameMessage.newBuilder().setMsgId(1001).setSeq(999).build());

        assertEquals(droppedBefore + 1, serverMetrics.getMessagesDropped());
    }

    @Test
    @DisplayName("close() stops accepting and sends POISON_PILL, consumer thread exits")
    void close_stopsConsumerThread() throws Exception {
        session.close();

        // 留出时间让消费线程退出
        Thread.sleep(200);

        // close 之后入队应被静默丢弃
        GameMessage msg = GameMessage.newBuilder().setMsgId(1001).setSeq(1).build();
        session.enqueue(msg);

        Thread.sleep(100);
        verify(channel, never()).writeAndFlush(any(GameMessage.class));

        session = null; // 避免 tearDown 中重复 close
    }

    @Test
    @DisplayName("awaitConsumerTermination sends POISON_PILL and waits for thread exit")
    void awaitConsumerTermination_exitsCleanly() {
        session.awaitConsumerTermination(5, TimeUnit.SECONDS);

        // 消费线程终止后不再处理任何消息
        session.enqueue(GameMessage.newBuilder().setMsgId(1001).setSeq(1).build());

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        verify(channel, never()).writeAndFlush(any(GameMessage.class));

        session = null; // 消费线程已终止
    }
}
