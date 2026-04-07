# 需求文档：休闲游戏服务器框架（game-server-framework）

## 简介

本文档基于已批准的设计文档，推导出休闲游戏服务器框架的 EARS 格式需求。
框架基于 Netty + Protobuf，集成于 Spring Boot 4.0 / Java 21 项目，通过消息号（msgId）将网络消息路由到对应的业务处理器（Handler），支持多种休闲游戏场景的快速接入。

---

## 词汇表

- **NettyServer**：负责启动 WebSocket 服务、监听端口、管理 Netty EventLoopGroup 生命周期的组件
- **GameChannelInitializer**：为每个新连接配置 ChannelPipeline 的组件
- **MessageDispatcher**：从 GameMessage 中提取 msgId，完成鉴权校验并将消息投入队列的 Netty Handler
- **MsgControllerRegistry**：在 Spring 启动时扫描 `@MsgController` Bean，建立 msgId → MethodInvoker 映射表的组件
- **GameSession**：封装单个客户端连接状态、专属消息队列和虚拟消费线程的对象
- **SessionManager**：管理所有在线 GameSession 的组件
- **SharedQueueManager**：管理进程内按 groupKey 串行消费的共享队列的组件
- **DistributedQueueManager**：使用 Redis List 实现跨进程消息队列的组件
- **GroupKeyResolver**：从 GameSession 中解析 groupKey 的业务层接口
- **ServerMetrics**：收集并定期打印运行指标的组件
- **GameMessage**：Protobuf 定义的统一消息包装器，包含 msg_id、seq、payload、error_code 字段
- **MethodInvoker**：封装反射调用所需信息（bean、method、payloadType、groupType、requireAuth）的记录类
- **GroupType**：消息串行消费维度枚举（USER / TEAM / GUILD / TEAM_DISTRIBUTED / GUILD_DISTRIBUTED）
- **MsgId**：由 `generateMsgId` Gradle 任务自动生成的消息号常量类
- **generateMsgId**：扫描 .proto 文件并生成 MsgId.java 的自定义 Gradle 任务
- **POISON_PILL**：用于通知消费线程优雅退出的特殊哨兵消息对象

---

## 需求

### 需求 1：WebSocket 服务启动与生命周期管理

**用户故事：** 作为游戏服务器运维人员，我希望框架能随 Spring Boot 应用自动启动和停止 Netty WebSocket 服务，以便无需手动管理网络层生命周期。

#### 验收标准

1. WHEN Spring 应用启动完成，THE NettyServer SHALL 在配置的端口上启动 WebSocket 服务并开始监听客户端连接
2. WHEN Spring 应用关闭，THE NettyServer SHALL 执行优雅停机流程，等待所有消息队列消费完毕后再关闭 EventLoopGroup
3. THE NettyServer SHALL 使用独立的 Boss 线程组（1 个线程）接受新连接，使用 Worker 线程组（CPU 核心数 × 2 个线程）处理 I/O
4. WHEN 优雅停机触发，THE NettyServer SHALL 停止接受新连接，最多等待 30 秒让所有用户队列和共享队列消费完毕
5. WHEN 优雅停机超时（30 秒），THE NettyServer SHALL 强制关闭剩余连接，不再等待队列清空

---

### 需求 2：连接管道初始化与协议编解码

**用户故事：** 作为框架使用者，我希望每个客户端连接都能自动完成 WebSocket 握手升级和 Protobuf 编解码配置，以便业务层直接处理强类型消息对象。

#### 验收标准

1. WHEN 新客户端连接建立，THE GameChannelInitializer SHALL 为该连接配置包含 HTTP 编解码、WebSocket 升级、Protobuf 编解码、心跳检测和消息分发的 ChannelPipeline
2. THE GameChannelInitializer SHALL 在 Pipeline 最前端检查当前在线连接数，WHEN 在线连接数达到 maxConnections 上限，THE GameChannelInitializer SHALL 直接关闭新连接且不发送任何响应
3. THE GameChannelInitializer SHALL 配置 WebSocket 升级路径为 `/ws`
4. THE GameChannelInitializer SHALL 配置心跳检测读空闲超时为 60 秒
5. THE GameChannelInitializer SHALL 配置 HTTP 请求体最大为 65536 字节（64KB）

---

### 需求 3：消息路由与分发

**用户故事：** 作为业务开发者，我希望框架能根据消息号自动将消息路由到对应的处理器，以便我只需关注业务逻辑而无需处理底层路由。

#### 验收标准

1. WHEN MessageDispatcher 收到 GameMessage，THE MessageDispatcher SHALL 从消息中提取 msgId 并查找对应的 MethodInvoker
2. WHEN MethodInvoker 的 requireAuth 为 true 且 GameSession 未认证，THE MessageDispatcher SHALL 向客户端返回 error_code=401 的 GameMessage 且不断开连接
3. WHEN GameSession 未认证且 msgId 未注册，THE MessageDispatcher SHALL 向客户端返回 error_code=401 的 GameMessage
4. WHEN MethodInvoker 的 groupType 为 USER 或 groupKey 解析为 null，THE MessageDispatcher SHALL 将消息投入 GameSession 的专属消息队列
5. WHEN MethodInvoker 的 groupType 为 TEAM 或 GUILD 且 groupKey 解析成功，THE MessageDispatcher SHALL 将消息投入 SharedQueueManager 中对应 groupKey 的共享队列
6. WHEN MethodInvoker 的 groupType 为 TEAM_DISTRIBUTED 或 GUILD_DISTRIBUTED 且 groupKey 解析成功，THE MessageDispatcher SHALL 将消息投入 DistributedQueueManager 中对应 groupKey 的 Redis 队列
7. WHEN 心跳超时（60 秒无读事件），THE MessageDispatcher SHALL 主动关闭对应 Channel
8. WHEN 框架处于停机状态，THE MessageDispatcher SHALL 不再将新消息投入任何队列

---

### 需求 4：消息处理器注册

**用户故事：** 作为业务开发者，我希望通过注解声明消息处理方法，框架在启动时自动完成注册，以便减少样板代码。

#### 验收标准

1. WHEN Spring 应用启动，THE MsgControllerRegistry SHALL 扫描所有标注了 `@MsgController` 的 Bean，并注册其中所有标注了 `@MsgMapping` 的方法
2. THE MsgControllerRegistry SHALL 为每个注册方法构建包含 bean、method、payloadType、groupType、requireAuth 信息的 MethodInvoker
3. WHEN 同一 msgId 被多个方法注册，THE MsgControllerRegistry SHALL 在启动时抛出 IllegalStateException 并终止启动（Fail-Fast）
4. WHEN `@MsgMapping` 方法的第二个参数不是 `com.google.protobuf.Message` 的子类，THE MsgControllerRegistry SHALL 在启动时抛出 IllegalStateException 并终止启动（Fail-Fast）
5. WHEN `@MsgMapping` 注解的 value 与方法第二个参数类名前缀 `C{msgId}_` 推导出的 msgId 不一致，THE MsgControllerRegistry SHALL 在启动时抛出 IllegalStateException 并终止启动（Fail-Fast）
6. WHEN 根据 msgId 查找 MethodInvoker 且该 msgId 未注册，THE MsgControllerRegistry SHALL 返回 null

---

### 需求 5：用户会话管理

**用户故事：** 作为框架使用者，我希望框架自动管理客户端连接的会话状态，以便业务层通过 GameSession 对象与客户端交互。

#### 验收标准

1. WHEN 新客户端连接建立（channelActive），THE SessionManager SHALL 创建一个新的 GameSession 并与该 Channel 关联
2. WHEN 客户端连接断开（channelInactive），THE SessionManager SHALL 从管理器中移除对应的 GameSession
3. THE SessionManager SHALL 支持通过 Channel 查找对应的 GameSession
4. THE SessionManager SHALL 支持通过 userId 查找对应的 GameSession
5. THE SessionManager SHALL 提供当前在线连接数的准确计数
6. WHEN 用户登录成功，THE GameSession SHALL 将 userId 绑定到当前 Session 并将 authenticated 标志设为 true
7. THE GameSession SHALL 提供向客户端发送 GameMessage 的方法

---

### 需求 6：用户专属消息队列与虚拟线程消费

**用户故事：** 作为框架使用者，我希望同一用户的消息能严格按顺序串行处理，且不阻塞 Netty I/O 线程，以便保证业务逻辑的正确性和服务器的高吞吐。

#### 验收标准

1. THE GameSession SHALL 拥有一个容量为 256 的有界阻塞消息队列（LinkedBlockingQueue）
2. THE GameSession SHALL 在创建时启动一个专属虚拟线程（Thread.ofVirtual()）用于串行消费消息队列
3. WHEN 消息队列已满（积压超过 256 条），THE GameSession SHALL 丢弃新消息并记录 WARN 日志，不断开连接
4. WHEN 消费线程从队列取出消息，THE GameSession SHALL 查找对应 MethodInvoker，反序列化 payload，并通过反射调用 Controller 方法
5. WHEN Controller 方法返回 Protobuf Message 子类，THE GameSession SHALL 自动将其包装为 GameMessage（msgId = 请求 msgId + 1，seq = 请求 seq）并发送给客户端
6. WHEN Controller 方法返回 void 或 null，THE GameSession SHALL 不发送任何响应（业务自行调用 session.send()）
7. WHEN 单条消息的 payload 反序列化或方法调用抛出异常，THE GameSession SHALL 记录错误日志并继续处理后续消息，不中断消费线程
8. WHEN 消费线程收到 POISON_PILL 消息，THE GameSession SHALL 退出消费循环并终止虚拟线程
9. WHEN GameSession 销毁，THE GameSession SHALL 向消息队列投入 POISON_PILL 以优雅停止消费线程

---

### 需求 7：单进程共享队列（SharedQueueManager）

**用户故事：** 作为业务开发者，我希望同一队伍或公会的消息在单进程内能串行处理，以便避免并发修改共享状态。

#### 验收标准

1. WHEN 消息的 groupType 为 TEAM 或 GUILD 且 groupKey 解析成功，THE SharedQueueManager SHALL 获取或创建该 groupKey 对应的 SharedQueue
2. THE SharedQueueManager SHALL 为每个 groupKey 维护一个容量为 1024 的有界队列和一个专属虚拟消费线程
3. WHEN SharedQueue 队列已满（积压超过 1024 条），THE SharedQueueManager SHALL 丢弃新消息并记录 WARN 日志
4. WHEN SharedQueue 空闲时间超过 5 分钟且队列为空，THE SharedQueueManager SHALL 中断消费线程并从内存中移除该 SharedQueue
5. THE SharedQueueManager SHALL 每 30 秒扫描一次所有 SharedQueue，清理空闲超时的队列

---

### 需求 8：跨进程分布式队列（DistributedQueueManager）

**用户故事：** 作为业务开发者，我希望在多进程部署场景下，同一队伍或公会的消息能跨进程串行处理，以便保证分布式环境下的业务一致性。

#### 验收标准

1. WHEN 消息的 groupType 为 TEAM_DISTRIBUTED 或 GUILD_DISTRIBUTED 且 groupKey 解析成功，THE DistributedQueueManager SHALL 使用 RPUSH 将消息序列化后推入 Redis List `game:queue:{groupKey}`
2. THE DistributedQueueManager SHALL 使用 SET NX PX 5000 尝试获取分布式锁 `game:lock:{groupKey}`，获取成功后使用 BLPOP 消费队列并在消费后释放锁
3. WHEN Redis 不可用，THE DistributedQueueManager SHALL 自动降级到本地 SharedQueueManager 处理消息，并记录 ERROR 日志
4. WHEN 分布式锁获取超时且超过最大重试次数，THE DistributedQueueManager SHALL 降级到本地 SharedQueueManager 并记录 WARN 日志
5. THE DistributedQueueManager SHALL 将 GroupMessage（session 元信息 + GameMessage bytes）序列化为 JSON 存入 Redis

---

### 需求 9：限流保护

**用户故事：** 作为游戏服务器运维人员，我希望框架能限制单个用户的消息发送频率，以便防止恶意用户通过消息洪泛拖垮服务器。

#### 验收标准

1. THE GameSession SHALL 内置令牌桶限流器（Guava RateLimiter），默认速率为每秒 30 个请求（可通过 `game.server.rate-limit.per-second` 配置）
2. WHEN 用户消息频率超过令牌桶速率，THE GameSession SHALL 静默丢弃超限消息，不向客户端发送错误响应
3. WHEN 消息被限流丢弃，THE ServerMetrics SHALL 递增 messagesDropped 计数器并记录 WARN 日志

---

### 需求 10：运行指标监控

**用户故事：** 作为游戏服务器运维人员，我希望能定期查看服务器的关键运行指标，以便及时发现性能问题。

#### 验收标准

1. THE ServerMetrics SHALL 追踪以下指标：当前在线连接数、累计连接总数、累计收到消息数、累计丢弃消息数
2. THE ServerMetrics SHALL 每 60 秒将所有关键指标以 INFO 级别打印到日志
3. WHEN 连接建立或断开，THE ServerMetrics SHALL 实时更新在线连接数计数器
4. WHEN 消息被丢弃（队列满或限流），THE ServerMetrics SHALL 递增 messagesDropped 计数器

---

### 需求 11：Protobuf 消息命名规范与 MsgId 自动生成

**用户故事：** 作为业务开发者，我希望框架能从 .proto 文件自动生成消息号常量类，以便避免手动维护消息号映射时出现错误。

#### 验收标准

1. THE generateMsgId 任务 SHALL 扫描 `src/main/proto/` 目录下所有 .proto 文件，提取符合 `C{msgId}_{MessageName}` 命名规范的消息定义
2. THE generateMsgId 任务 SHALL 读取 `src/main/proto/msg-modules.properties` 配置文件，按 msgId 范围将消息号归类到对应模块
3. THE generateMsgId 任务 SHALL 生成 `src/main/java/com/rainnov/framework/proto/MsgId.java`，按模块组织嵌套静态类，每个消息号对应一个 `public static final int` 常量
4. WHEN msgId 不在 msg-modules.properties 定义的任何范围内，THE generateMsgId 任务 SHALL 使用 `MODULE_{base}` 作为模块名（base 为 msgId 向下取整到千位）
5. THE generateMsgId 任务 SHALL 在 `generateProto` 任务之后、`compileJava` 任务之前自动执行
6. THE MsgControllerRegistry SHALL 在注册时校验 `@MsgMapping` 注解的 value 与参数类名前缀 `C{msgId}_` 推导出的 msgId 是否一致，不一致时 Fail-Fast

---

### 需求 12：错误处理

**用户故事：** 作为框架使用者，我希望框架能妥善处理各类异常情况并返回标准错误响应，以便客户端能正确处理错误。

#### 验收标准

1. WHEN msgId 未注册（找不到 MethodInvoker），THE GameSession SHALL 向客户端返回 error_code=404 的 GameMessage
2. WHEN payload 反序列化失败（InvalidProtocolBufferException），THE GameSession SHALL 向客户端返回 error_code=400 的 GameMessage 并记录日志
3. WHEN Controller 方法抛出异常，THE GameSession SHALL 向客户端返回 error_code=500 的 GameMessage 并记录日志
4. WHEN Protobuf 解码失败，THE NettyServer SHALL 通过 ExceptionCaught 关闭对应连接
5. WHEN groupKey 解析返回 null，THE MessageDispatcher SHALL 将消息降级到用户专属队列并记录 DEBUG 日志

---

### 需求 13：业务 Controller 开发接口

**用户故事：** 作为业务开发者，我希望通过简洁的注解声明消息处理逻辑，框架自动完成路由、反序列化和响应发送，以便专注于业务实现。

#### 验收标准

1. THE 框架 SHALL 提供 `@MsgController` 注解，标注在类上表示该类为消息处理模块，同时注册为 Spring Bean
2. THE 框架 SHALL 提供 `@MsgMapping` 注解，标注在方法上，声明处理的消息号（value）、串行维度（groupBy）和鉴权要求（requireAuth）
3. WHEN `@MsgMapping` 方法签名为 `(GameSession, XxxReq)`，THE 框架 SHALL 自动反序列化 payload 为第二个参数类型并通过反射调用该方法
4. THE `@MsgMapping` 注解 SHALL 支持 `requireAuth` 属性，默认为 true；设为 false 时该接口无需登录即可访问
5. THE `@MsgMapping` 注解 SHALL 支持 `groupBy` 属性，默认为 GroupType.USER；支持 TEAM、GUILD、TEAM_DISTRIBUTED、GUILD_DISTRIBUTED 维度的串行消费
6. THE 框架 SHALL 提供 `GroupKeyResolver` 接口，业务层实现并注册为 Spring Bean，用于从 GameSession 中解析 groupKey；WHEN 无法解析时返回 null，消息降级到用户队列
