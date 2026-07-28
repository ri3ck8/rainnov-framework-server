# 项目结构

## 包结构

```
com.rainnov/
├── GameServerApplication.java         # Spring Boot 启动类
├── framework/                         # 框架核心（不放业务逻辑）
│   ├── net/
│   │   ├── dispatch/                  # 注解驱动的消息路由
│   │   │   ├── MsgController.java         # @MsgController — 标记消息处理类（同时是 Spring @Component）
│   │   │   ├── MsgMapping.java            # @MsgMapping — msgId → 方法，支持 requireAuth
│   │   │   └── MsgControllerRegistry.java # 启动时扫描 Bean，构建 msgId → MethodInvoker 映射
│   │   ├── server/                    # Netty 服务器基础设施
│   │   │   ├── NettyServer.java           # 启动/停机、EventLoopGroup 生命周期
│   │   │   ├── GameChannelInitializer.java# Pipeline 装配、最大连接数拦截
│   │   │   ├── MessageDispatcher.java     # 限流、心跳短路、鉴权、入队
│   │   │   └── ServerMetrics.java         # 运行指标
│   │   └── session/                   # 客户端会话
│   │       ├── GameSession.java           # 连接状态 + 专属队列 + 虚拟线程消费者
│   │       └── SessionManager.java        # 会话集合与查找
│   └── proto/
│       └── MsgId.java                 # 自动生成 — 禁止编辑
├── modules/                           # 业务模块（每个模块一个子包）
│   ├── user/                          # 登录/登出（UserController）
│   └── inventory/                     # 背包系统
│       ├── InventoryController.java
│       ├── InventoryService.java
│       ├── InventoryErrorCode.java
│       ├── config/                    # ItemConfigRegistry
│       ├── model/                     # Slot / PlayerInventory / ItemConfig / 枚举 / Snapshot
│       └── effect/                    # 物品效果处理器（策略模式）
└── client/
    └── GameClient.java                # 测试用 WebSocket 客户端
```

消息统一按用户维度串行消费，框架不再提供分组（队伍/公会）队列与 Redis 分布式队列，详见 [architecture.md](architecture.md#跨用户共享状态)。

## Proto 源文件

```
src/main/proto/
├── game_message.proto      # GameMessage 统一包装器 + 心跳消息（C1/C2）
├── user.proto              # 登录/登出消息
├── inventory.proto         # 背包模块消息
└── msg-modules.properties  # 消息号范围 → 模块名映射（1000=LOGIN, 5000=INVENTORY 等）
```

## 测试目录

测试目录镜像 main 包结构：

```
src/test/java/com/rainnov/
└── framework/
    ├── GameServerApplicationTests.java
    ├── net/dispatch/MsgControllerRegistryTest.java
    ├── net/server/MessageDispatcherTest.java
    ├── net/session/GameSessionTest.java
    ├── net/session/SessionManagerTest.java
    └── proto/MsgIdTest.java
```

约定详见 [testing.md](testing.md)。

## 文档目录

```
AGENTS.md                   # 索引入口
docs/
├── product.md
├── tech-stack.md
├── project-structure.md
├── architecture.md
├── protocol.md
├── conventions.md
├── testing.md
├── properties.md           # 正确性属性清单
├── requirements/*.md       # EARS 验收标准（framework / inventory）
├── modules/inventory.md
└── guides/*.md             # 新模块开发指引
```
