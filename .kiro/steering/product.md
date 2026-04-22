# 产品概述

Rainnov Framework Server — 基于 Netty + Protobuf + Spring Boot 的休闲游戏服务器框架。

通过消息号（msgId）将 WebSocket 二进制帧路由到注解标注的 Handler 方法，提供类似 Spring MVC 的游戏服务器开发体验。

## 核心能力

- Netty WebSocket + Protobuf 二进制协议，异步非阻塞 I/O
- 注解驱动消息路由：`@MsgController` + `@MsgMapping` 自动注册
- 每用户独立消息队列 + Java 21 虚拟线程，保证单用户消息串行
- 按队伍/公会维度的共享队列（进程内 `SharedQueueManager` / 跨进程 Redis `DistributedQueueManager`）
- Guava 令牌桶限流、心跳检测、最大连接数限制
- 优雅停机：停止入队 → 等待队列消费 → 关闭连接
- `MsgId.java` 由 Gradle 任务从 `.proto` 文件自动生成
- 内置 `ServerMetrics` 定期输出运行指标

## 目标场景

轻量级、高并发休闲游戏服务器。按用户（或按组）保证消息顺序，业务开发者只需编写 Controller 方法。

## 消息流转

```
客户端 BinaryWebSocketFrame
  → GameChannelInitializer Pipeline 解码为 GameMessage
  → MessageDispatcher（限流 → 心跳短路 → 鉴权校验）
  → 按 @MsgMapping.groupBy 路由到队列（USER / TEAM / GUILD / *_DISTRIBUTED）
  → 虚拟线程消费者反序列化 payload → 反射调用 Handler → 自动包装响应
```
