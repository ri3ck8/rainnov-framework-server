# 产品概述

Rainnov Framework Server 是基于 Netty + Protobuf + Spring Boot 构建的休闲游戏服务器框架。

通过消息号（msgId）将 WebSocket 二进制消息路由到注解标注的处理方法，提供类似 Spring MVC 的游戏服务器开发体验。

## 核心能力

- 基于 Netty 的 WebSocket + Protobuf 二进制协议，异步非阻塞 I/O
- 注解驱动的消息路由：`@MsgController` + `@MsgMapping` 自动注册处理器
- 每用户独立消息队列 + Java 21 虚拟线程，保证单用户消息串行处理
- 支持按队伍/公会维度的共享队列串行消费（单进程内 & 跨进程 Redis 分布式队列）
- Guava 令牌桶限流、心跳检测、最大连接数限制
- 优雅停机：停止入队 → 等待队列消费完毕 → 关闭连接
- `MsgId.java` 由 Gradle 任务从 `.proto` 文件自动生成
- 内置 `ServerMetrics` 定期输出运行指标日志

## 目标场景

轻量级、高并发的休闲游戏服务器，要求按用户（或按组）保证消息顺序，业务开发者只需编写 Controller 方法。
