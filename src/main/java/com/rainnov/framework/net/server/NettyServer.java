package com.rainnov.framework.net.server;

import com.rainnov.framework.net.session.GameSession;
import com.rainnov.framework.net.session.SessionManager;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket 服务器，与 Spring 生命周期集成。
 * 启动时绑定端口并监听客户端连接，关闭时执行优雅停机流程。
 */
@Slf4j
@Component
public class NettyServer implements InitializingBean, DisposableBean {

    private volatile boolean shuttingDown = false;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private final GameChannelInitializer gameChannelInitializer;
    private final SessionManager sessionManager;
    private final MessageDispatcher messageDispatcher;

    @Value("${game.server.port:8888}")
    private int port;

    public NettyServer(GameChannelInitializer gameChannelInitializer,
                       SessionManager sessionManager,
                       MessageDispatcher messageDispatcher) {
        this.gameChannelInitializer = gameChannelInitializer;
        this.sessionManager = sessionManager;
        this.messageDispatcher = messageDispatcher;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        start(port);

        // 注册 JVM ShutdownHook 触发优雅停机
        Runtime.getRuntime().addShutdownHook(new Thread(this::initiateGracefulShutdown));
    }

    /**
     * 绑定端口并启动 WebSocket 服务。
     * bossGroup 使用 1 个线程接受新连接，workerGroup 使用默认线程数（CPU 核心数 × 2）处理 I/O。
     */
    public void start(int port) throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(0);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
         .channel(NioServerSocketChannel.class)
         .childHandler(gameChannelInitializer);

        serverChannel = b.bind(port).sync().channel();
        log.info("NettyServer started on port {}", port);
    }

    /**
     * 优雅停机流程：
     * 1. 设置停机标志
     * 2. 通知 MessageDispatcher 停止入队
     * 3. 通知所有 Session 停止接受新消息
     * 4. 等待所有用户队列消费完毕（最多 30s）
     * 5. 关闭 serverChannel
     * 6. 关闭 Boss/Worker EventLoopGroup
     */
    public void initiateGracefulShutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        log.info("开始优雅停机...");

        // 通知 MessageDispatcher 停止入队
        messageDispatcher.setShuttingDown(true);

        // 通知所有 Session 停止接受新消息
        for (GameSession session : sessionManager.getAllSessions()) {
            session.stopAcceptingMessages();
        }

        // 等待所有用户队列消费完毕
        for (GameSession session : sessionManager.getAllSessions()) {
            session.awaitConsumerTermination(30, TimeUnit.SECONDS);
        }

        // 关闭 serverChannel
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 关闭 EventLoopGroup
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }

        log.info("优雅停机完成");
    }

    @Override
    public void destroy() {
        initiateGracefulShutdown();
    }
}
