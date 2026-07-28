# 需求：游戏服务器框架（EARS）

框架层的验收标准，EARS 格式。编号保持稳定，[properties.md](../properties.md) 中的属性按本文编号引用。

> 原分组队列需求（需求 7 SharedQueueManager、需求 8 DistributedQueueManager）已随该子系统移除而作废，编号保留为空位，不再复用。跨用户共享状态改由业务侧显式加锁，见 [architecture.md](../architecture.md#跨用户共享状态)。

## 词汇表

- **NettyServer**：启动 WebSocket 服务、监听端口、管理 Netty EventLoopGroup 生命周期的组件
- **GameChannelInitializer**：为每个新连接配置 ChannelPipeline 的组件
- **MessageDispatcher**：提取 msgId、限流、心跳短路、鉴权校验并入队的 Netty Handler
- **MsgControllerRegistry**：启动时扫描 `@MsgController` Bean，建立 msgId → MethodInvoker 映射的组件
- **GameSession**：封装单连接状态、专属消息队列和虚拟消费线程的对象
- **SessionManager**：管理所有在线 GameSession 的组件
- **ServerMetrics**：收集并定期打印运行指标的组件
- **GameMessage**：Protobuf 统一消息包装器（msg_id / seq / payload / error_code）
- **MethodInvoker**：封装反射调用信息（bean、method、payloadType、requireAuth）的 record
- **MsgId**：由 `generateMsgId` Gradle 任务生成的消息号常量类
- **POISON_PILL**：通知消费线程优雅退出的哨兵消息

## 需求 1：WebSocket 服务启动与生命周期管理

作为运维人员，希望网络层随 Spring Boot 应用自动启停。

1. WHEN Spring 应用启动完成，THE NettyServer SHALL 在配置端口启动 WebSocket 服务并开始监听
2. WHEN Spring 应用关闭，THE NettyServer SHALL 执行优雅停机，等待消息队列消费完毕后再关闭 EventLoopGroup
3. THE NettyServer SHALL 使用 Boss 线程组（1 线程）接受连接，Worker 线程组（CPU 核数 × 2）处理 I/O
4. WHEN 优雅停机触发，THE NettyServer SHALL 停止入队，最多等待 30 秒让所有用户队列消费完毕
5. WHEN 优雅停机超时（30 秒），THE NettyServer SHALL 强制关闭剩余连接，不再等待队列清空

## 需求 2：连接管道初始化与协议编解码

1. WHEN 新连接建立，THE GameChannelInitializer SHALL 配置包含 HTTP 编解码、WebSocket 升级、Protobuf 编解码、心跳检测与消息分发的 ChannelPipeline
2. THE GameChannelInitializer SHALL 在 Pipeline 最前端检查在线连接数，WHEN 达到 `max-connections` 上限，SHALL 直接关闭新连接且不发送任何响应
3. THE GameChannelInitializer SHALL 配置 WebSocket 升级路径为 `/ws`
4. THE GameChannelInitializer SHALL 配置读空闲超时为 60 秒
5. THE GameChannelInitializer SHALL 限制 HTTP 请求体最大 65536 字节（64KB）

## 需求 3：消息路由与分发

1. WHEN MessageDispatcher 收到 GameMessage，SHALL 提取 msgId 并查找对应 MethodInvoker
2. WHEN MethodInvoker 的 requireAuth 为 true 且 Session 未认证，SHALL 返回 `error_code=401` 且不断开连接
3. WHEN Session 未认证且 msgId 未注册，SHALL 返回 `error_code=401`
4. WHEN 消息通过限流与鉴权校验，SHALL 将其投入该 Session 的专属消息队列（统一按用户维度串行）
5. WHEN msgId 为 `MsgId.SYSTEM.HEARTBEAT_REQ`，SHALL 在 I/O 线程直接回复心跳响应，不进入业务队列；解析失败时返回 `error_code=400`
6. WHEN 心跳超时（60 秒无读事件），SHALL 主动关闭对应 Channel
7. WHEN 框架处于停机状态，SHALL 不再将新消息投入队列

## 需求 4：消息处理器注册

1. WHEN Spring 应用启动，THE MsgControllerRegistry SHALL 扫描所有 `@MsgController` Bean 并注册其中所有 `@MsgMapping` 方法
2. THE MsgControllerRegistry SHALL 为每个注册方法构建包含 bean、method、payloadType、requireAuth 的 MethodInvoker
3. WHEN 同一 msgId 被多个方法注册，SHALL 启动时抛出 `IllegalStateException`（Fail-Fast）
4. WHEN 方法签名不是 `(GameSession, ? extends com.google.protobuf.Message)`，SHALL 启动时抛出 `IllegalStateException`（Fail-Fast）
5. WHEN `@MsgMapping` 的 value 与第二个参数类名前缀 `C{msgId}_` 推导的 msgId 不一致，SHALL 启动时抛出 `IllegalStateException`（Fail-Fast）
6. WHEN 查找未注册的 msgId，THE MsgControllerRegistry SHALL 返回 null

## 需求 5：用户会话管理

1. WHEN 连接建立（channelActive），THE SessionManager SHALL 创建 GameSession 并与 Channel 关联
2. WHEN 连接断开（channelInactive），THE SessionManager SHALL 移除对应 GameSession
3. THE SessionManager SHALL 支持按 Channel 查找 GameSession
4. THE SessionManager SHALL 支持按 userId 查找 GameSession
5. THE SessionManager SHALL 提供准确的在线连接数
6. WHEN 登录成功，THE GameSession SHALL 绑定 userId 并将 authenticated 置为 true
7. THE GameSession SHALL 提供向客户端发送 GameMessage 的方法

## 需求 6：用户专属消息队列与虚拟线程消费

1. THE GameSession SHALL 拥有容量 256 的有界阻塞队列（`LinkedBlockingQueue`）
2. THE GameSession SHALL 在创建时启动专属虚拟线程（`Thread.ofVirtual()`）串行消费队列
3. WHEN 队列已满（积压超过 256 条），SHALL 丢弃新消息、递增 messagesDropped 并记 WARN，不断开连接
4. WHEN 消费线程取出消息，SHALL 查找 MethodInvoker、反序列化 payload、反射调用 Controller 方法
5. WHEN Controller 方法返回 Protobuf Message，SHALL 自动包装为 GameMessage（msgId = 请求 + 1，seq = 请求 seq）发送
6. WHEN Controller 方法返回 void 或 null，SHALL 不发送任何响应
7. WHEN 单条消息反序列化或调用抛异常，SHALL 记日志并继续处理后续消息，不中断消费线程
8. WHEN 消费线程收到 POISON_PILL，SHALL 退出消费循环并终止虚拟线程
9. WHEN GameSession 销毁，SHALL 投入 POISON_PILL 以优雅停止消费线程

## 需求 7、8（已废弃）

分组队列（`SharedQueueManager`）与跨进程分布式队列（`DistributedQueueManager`）需求已移除。

## 需求 9：限流保护

1. THE GameSession SHALL 内置 Guava 令牌桶，默认 30 req/s（`game.server.rate-limit.per-second`）
2. WHEN 消息频率超过令牌桶速率，SHALL 静默丢弃，不发送错误响应
3. WHEN 消息被限流丢弃，THE ServerMetrics SHALL 递增 messagesDropped 计数器

## 需求 10：运行指标监控

1. THE ServerMetrics SHALL 追踪在线连接数、累计连接数、累计收到消息数、累计丢弃消息数
2. THE ServerMetrics SHALL 每 60 秒以 INFO 打印指标摘要
3. WHEN 连接建立或断开，SHALL 实时更新在线连接数
4. WHEN 消息被丢弃（队列满或限流），SHALL 递增 messagesDropped

## 需求 11：Protobuf 命名规范与 MsgId 自动生成

1. THE generateMsgId 任务 SHALL 扫描 `src/main/proto/` 下所有 `.proto`，提取 `C{msgId}_{MessageName}` 消息
2. THE generateMsgId 任务 SHALL 读取 `msg-modules.properties`，按 msgId 范围归类到模块
3. THE generateMsgId 任务 SHALL 生成 `MsgId.java`，按模块组织嵌套静态类与 `public static final int` 常量
4. WHEN msgId 不在任何已定义范围内，SHALL 使用 `MODULE_{base}` 作为模块名（base 为向下取整到千位）
5. THE generateMsgId 任务 SHALL 在 `generateProto` 之后、`compileJava` 之前自动执行
6. THE MsgControllerRegistry SHALL 校验 `@MsgMapping` value 与参数类名前缀推导的 msgId 一致，不一致 Fail-Fast

## 需求 12：错误处理

1. WHEN msgId 未注册，THE GameSession SHALL 返回 `error_code=404`
2. WHEN payload 反序列化失败，SHALL 返回 `error_code=400` 并记日志
3. WHEN Controller 方法抛异常，SHALL 返回 `error_code=500` 并记日志
4. WHEN Protobuf 解码失败，SHALL 通过 `exceptionCaught` 关闭连接
5. WHEN 心跳消息解析失败，THE MessageDispatcher SHALL 返回 `error_code=400` 并记 WARN

## 需求 13：业务 Controller 开发接口

1. THE 框架 SHALL 提供 `@MsgController` 注解（标注在类上，同时注册为 Spring Bean）
2. THE 框架 SHALL 提供 `@MsgMapping` 注解，声明消息号（value）与鉴权要求（requireAuth）
3. WHEN 方法签名为 `(GameSession, XxxReq)`，THE 框架 SHALL 自动反序列化 payload 并反射调用
4. THE `@MsgMapping` SHALL 支持 `requireAuth`，默认 true；设为 false 时免登录访问
