# 任务列表：休闲游戏服务器框架（game-server-framework）

## 任务

- [x] 1 项目基础配置与依赖
  - [x] 1.1 在 build.gradle 中添加 Netty、Protobuf、Guava 依赖及 Protobuf Gradle 插件
  - [x] 1.2 创建 src/main/proto/msg-modules.properties 模块范围配置文件
  - [x] 1.3 创建 src/main/proto/game_message.proto（GameMessage、HeartbeatReq、HeartbeatResp）
  - [x] 1.4 配置 build.gradle 中的 protobuf 编译任务（generateProto）

- [x] 2 generateMsgId Gradle 任务
  - [x] 2.1 在 buildSrc 或 build.gradle 中实现 generateMsgId 任务，扫描 .proto 文件提取 C{msgId}_ 消息
  - [x] 2.2 实现 resolveModule 函数，按 msg-modules.properties 范围映射归类模块名
  - [x] 2.3 实现 MsgId.java 代码生成逻辑（嵌套静态类 + public static final int 常量）
  - [x] 2.4 配置任务依赖：generateMsgId.dependsOn generateProto，compileJava.dependsOn generateMsgId

- [x] 3 协议与注解定义
  - [x] 3.1 创建 GroupType 枚举（USER / TEAM / GUILD / TEAM_DISTRIBUTED / GUILD_DISTRIBUTED）
  - [x] 3.2 创建 @MsgController 注解（TYPE 级别，包含 @Component）
  - [x] 3.3 创建 @MsgMapping 注解（METHOD 级别，包含 value、groupBy、requireAuth 属性）
  - [x] 3.4 创建 GroupKeyResolver 接口（resolve 方法）

- [x] 4 GameSession
  - [x] 4.1 实现 GameSession 类，包含 sessionId、channel、userId、authenticated、createTime、lastActiveTime 字段
  - [x] 4.2 实现容量 256 的 LinkedBlockingQueue<GameMessage> 和 POISON_PILL 哨兵常量
  - [x] 4.3 实现 Guava RateLimiter 令牌桶（默认 30 req/s，可配置）及 tryAcquireRateLimit() 方法
  - [x] 4.4 实现 enqueue() 方法（检查 acceptingMessages 标志，队列满时丢弃并记录 WARN）
  - [x] 4.5 实现 buildConsumerTask() 虚拟线程消费逻辑（查找 MethodInvoker、反序列化、反射调用、自动响应包装）
  - [x] 4.6 实现 send()、close()、bindUser()、stopAcceptingMessages()、awaitConsumerTermination() 方法
  - [x] 4.7 在构造函数中启动专属虚拟线程（Thread.ofVirtual()）

- [x] 5 SessionManager
  - [x] 5.1 实现 SessionManager，使用 ConcurrentHashMap 维护 channel→session 和 userId→session 映射
  - [x] 5.2 实现 createSession()、removeSession()、getByChannel()、getByUserId()、onlineCount()、getAllSessions() 方法

- [x] 6 MsgControllerRegistry
  - [x] 6.1 实现 MethodInvoker record（bean、method、payloadType、groupType、requireAuth）
  - [x] 6.2 实现 afterPropertiesSet()，扫描 @MsgController Bean 并注册 @MsgMapping 方法
  - [x] 6.3 实现方法签名校验（第二个参数必须是 Message 子类）及 Fail-Fast 异常
  - [x] 6.4 实现 msgId 重复检测及 Fail-Fast 异常
  - [x] 6.5 实现 @MsgMapping value 与参数类名前缀 C{msgId}_ 一致性校验及 Fail-Fast 异常
  - [x] 6.6 实现 find(msgId) 和 registeredMsgIds() 方法

- [x] 7 SharedQueueManager
  - [x] 7.1 实现 SharedQueue 内部类（容量 1024 的 LinkedBlockingQueue + 虚拟消费线程 + lastActiveTime）
  - [x] 7.2 实现 SharedQueueManager.getOrCreate(groupKey)（线程安全，ConcurrentHashMap）
  - [x] 7.3 实现 SharedQueue.enqueue()（非阻塞，满时丢弃并记录 WARN）
  - [x] 7.4 实现空闲超时清理定时任务（每 30s 扫描，空闲 5 分钟且队列为空时销毁）
  - [x] 7.5 实现 awaitAllQueues(timeout, unit) 方法（优雅停机用）

- [x] 8 DistributedQueueManager
  - [x] 8.1 实现 GroupMessage record（GameSession 元信息 + GameMessage bytes）及 JSON 序列化/反序列化
  - [x] 8.2 实现 enqueue(groupKey, message)（RPUSH game:queue:{groupKey}）
  - [x] 8.3 实现 startConsumer(groupKey)（SET NX PX 5000 获取锁 → BLPOP 消费 → 释放锁）
  - [x] 8.4 实现 Redis 不可用时自动降级到 SharedQueueManager 的逻辑，记录 ERROR 日志
  - [x] 8.5 实现分布式锁超时重试及降级逻辑，记录 WARN 日志

- [x] 9 MessageDispatcher
  - [x] 9.1 实现 channelActive()（调用 SessionManager.createSession()）
  - [x] 9.2 实现 channelInactive()（调用 SessionManager.removeSession()）
  - [x] 9.3 实现 channelRead0() 核心路由逻辑（鉴权校验 → groupType 判断 → 入队）
  - [x] 9.4 实现 groupKey 为 null 时降级到用户队列的逻辑，记录 DEBUG 日志
  - [x] 9.5 实现停机状态检查（shuttingDown 标志）
  - [x] 9.6 实现 userEventTriggered() 处理心跳超时（IdleStateEvent → 关闭 Channel）

- [x] 10 GameChannelInitializer
  - [x] 10.1 实现 initChannel()，配置完整 ChannelPipeline（HTTP 编解码、WebSocket 升级、Protobuf 编解码、心跳、MessageDispatcher）
  - [x] 10.2 在 Pipeline 最前端添加最大连接数检查（超限直接关闭，不发送响应）

- [x] 11 NettyServer
  - [x] 11.1 实现 NettyServer（InitializingBean + DisposableBean），配置 Boss/Worker EventLoopGroup
  - [x] 11.2 实现 start(port) 方法，绑定端口并启动 WebSocket 服务
  - [x] 11.3 实现 initiateGracefulShutdown() 优雅停机流程（停止入队 → 等待队列消费 → 关闭 EventLoopGroup）
  - [x] 11.4 注册 JVM ShutdownHook 触发优雅停机

- [x] 12 ServerMetrics
  - [x] 12.1 实现 ServerMetrics，使用 AtomicLong 维护 onlineConnections、totalConnections、messagesReceived、messagesDropped 计数器
  - [x] 12.2 实现 @Scheduled(fixedDelay=60000) 定期打印指标日志
  - [x] 12.3 在 SessionManager、GameSession、MessageDispatcher 中的关键路径调用 ServerMetrics 更新计数器

- [x] 13 示例业务 Controller 与 Proto 文件
  - [x] 13.1 创建 src/main/proto/login.proto（C1001_LoginReq、C1002_LoginResp、C1003_LogoutReq）
  - [x] 13.2 创建 LoginController 示例（@MsgController，login 方法 requireAuth=false，logout 方法默认 requireAuth=true）
  - [x] 13.3 创建 GameGroupKeyResolver 示例实现（从 GameSession 解析 teamId/guildId）

- [x] 14 单元测试
  - [x] 14.1 MsgControllerRegistry 单元测试（注解扫描、签名校验、重复 msgId、命名一致性校验）
  - [x] 14.2 MessageDispatcher 单元测试（Mock Session/Registry，验证路由逻辑、未认证拦截）
  - [x] 14.3 SessionManager 单元测试（Session 创建/销毁/查找/计数）
  - [x] 14.4 GameSession 单元测试（消息入队/消费、自动响应包装、异常隔离、POISON_PILL 退出）
  - [x] 14.5 SharedQueueManager 单元测试（getOrCreate 幂等性、空闲超时销毁）
  - [x] 14.6 generateMsgId 任务单元测试（消息解析、模块归类、范围外 MODULE_{base} 命名）
