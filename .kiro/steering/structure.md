# 项目结构与架构

## 包结构

```
com.rainnov/
├── framework/                         # 框架核心
│   ├── net/
│   │   ├── dispatch/                  # 注解驱动的消息路由
│   │   │   ├── MsgController.java        # @MsgController — 标记消息处理类（同时也是 Spring @Component）
│   │   │   ├── MsgMapping.java           # @MsgMapping — 将 msgId 映射到处理方法，支持 groupBy 和 requireAuth
│   │   │   └── MsgControllerRegistry.java # 启动时扫描 @MsgController Bean，构建 msgId → MethodInvoker 映射
│   │   ├── server/                    # Netty 服务器基础设施
│   │   │   ├── NettyServer.java           # 服务器生命周期（启动、优雅停机），Spring InitializingBean
│   │   │   ├── GameChannelInitializer.java # Channel Pipeline：HTTP → WebSocket → Protobuf 编解码 → 分发器
│   │   │   ├── MessageDispatcher.java     # @Sharable 处理器：鉴权校验、心跳短路、队列路由
│   │   │   └── ServerMetrics.java         # AtomicLong 计数器，定期日志输出
│   │   ├── session/                   # 客户端会话管理
│   │   │   ├── GameSession.java           # 单连接状态、每用户消息队列 + 虚拟线程消费者
│   │   │   └── SessionManager.java        # Channel→Session 和 userId→Session 查找映射
│   │   └── queue/                     # 基于分组的串行消息消费
│   │       ├── GroupType.java             # 枚举：USER、TEAM、GUILD、TEAM_DISTRIBUTED、GUILD_DISTRIBUTED
│   │       ├── GroupKeyResolver.java      # 从 Session 解析 groupKey 的业务层接口
│   │       ├── GameGroupKeyResolver.java  # 示例 GroupKeyResolver，解析队伍/公会 Key
│   │       ├── GroupMessage.java          # 包装 GameSession + GameMessage 的 Record
│   │       ├── SharedQueueManager.java    # 进程内按 groupKey 的共享队列，虚拟线程消费
│   │       ├── DistributedQueueManager.java # 基于 Redis 的跨进程队列，分布式锁保证串行
│   │       └── DistributedGroupMessage.java # 用于 Redis 存储的可序列化 DTO
│   └── proto/                         # 自动生成
│       └── MsgId.java                     # 禁止编辑 — 由 Gradle 任务从 .proto 文件生成
├── modules/                           # 业务模块
│   ├── user/
│   │   └── UserController.java            # @MsgController，包含登录/登出处理器
│   └── inventory/                     # 背包模块
│       ├── InventoryController.java       # @MsgController，背包消息处理
│       ├── InventoryService.java          # 核心业务逻辑
│       ├── InventoryErrorCode.java        # 错误码常量
│       ├── config/
│       │   └── ItemConfigRegistry.java    # 物品配置注册中心
│       ├── model/
│       │   ├── PlayerInventory.java       # 玩家背包内存模型
│       │   ├── Slot.java                  # 格子数据
│       │   ├── SlotSnapshot.java          # 格子快照
│       │   ├── ItemConfig.java            # 物品配置
│       │   ├── ItemType.java              # 物品类型枚举
│       │   ├── ExpirationMode.java        # 过期模式枚举
│       │   └── ExpirationPolicy.java      # 过期策略
│       └── effect/
│           ├── ItemEffectHandler.java     # 效果处理器接口
│           ├── ItemEffectHandlerRegistry.java # 效果处理器注册中心
│           ├── ItemEffectContext.java      # 效果上下文
│           └── HealingEffectHandler.java  # 示例：治疗效果处理器
└── client/
    └── GameClient.java                # 测试用 WebSocket 客户端，支持心跳和控制台命令
```

## Proto 源文件

```
src/main/proto/
├── game_message.proto      # GameMessage 统一包装器（msgId、seq、payload、errorCode）+ 心跳消息
├── user.proto             # 登录/登出请求响应消息
└── msg-modules.properties  # 消息号范围 → 模块名映射（如 1000=LOGIN、2000=ROOM）
```

## 关键约定

- Proto 消息命名：`C{msgId}_{MessageName}`（如 `C1001_LoginReq`）。`generateMsgId` 任务依赖此命名模式。
- 响应消息号 = 请求消息号 + 1（Handler 返回 `Message` 时框架自动包装发送）。
- Controller 方法签名：`(GameSession session, XxxReq req)` — 第一个参数固定为 `GameSession`，第二个为 Protobuf 请求类型。
- 返回 Protobuf `Message` 则自动发送响应；返回 `void` 则由业务自行调用 `session.send()`。
- `@MsgMapping(requireAuth = false)` 用于无需鉴权的接口（如登录）。默认值为 `true`。
- `@MsgMapping` 的 `groupBy` 控制队列路由：`USER`（默认，按用户）、`TEAM`/`GUILD`（进程内共享队列）、`TEAM_DISTRIBUTED`/`GUILD_DISTRIBUTED`（Redis 分布式队列）。
- `GroupKeyResolver` 实现类必须注册为 Spring `@Component` Bean。
- `MsgId.java` 为自动生成文件 — 禁止手动编辑。修改 `.proto` 文件后运行 `./gradlew generateMsgId`。
- 模块范围在 `msg-modules.properties` 中配置（如 `1000=LOGIN`、`2000=ROOM`）。

## 消息流转

1. 客户端发送包含序列化 `GameMessage` 的 `BinaryWebSocketFrame`
2. `GameChannelInitializer` Pipeline 解码为 `GameMessage`
3. `MessageDispatcher` 检查限流、处理心跳短路、校验鉴权
4. 根据 `@MsgMapping.groupBy` 路由到：用户队列（`GameSession.enqueue`）、共享队列（`SharedQueueManager`）或分布式队列（`DistributedQueueManager`）
5. 虚拟线程消费者反序列化 payload，通过反射调用 Handler，自动包装响应
