# Rainnov Framework Server

基于 **Netty + Protobuf + Spring Boot 4.1 + Java 25** 的休闲游戏服务器框架。

通过消息号（Message ID）将网络消息路由到对应的业务处理器，支持注解驱动开发，业务开发者只需关注业务逻辑。

## 特性

- WebSocket + Protobuf 二进制协议，高性能异步非阻塞 I/O
- `@MsgController` + `@MsgMapping` 注解自动注册消息处理器，类似 Spring MVC 的开发体验
- 每用户独立消息队列 + Java 25 虚拟线程，天然保证单用户消息串行
- 跨用户共享状态（队伍、公会等）由业务侧用锁或分布式锁显式控制，框架不做隐式串行
- Guava 令牌桶限流、心跳检测、最大连接数限制
- 优雅停机：停止入队 → 等待队列消费完毕 → 关闭连接
- `MsgId.java` 由 Gradle 任务从 `.proto` 文件自动生成，零手动维护
- 内置 ServerMetrics 运行指标定期日志输出

## 项目结构

```
src/main/java/com/rainnov/
├── framework/                  # 框架核心
│   ├── net/
│   │   ├── server/             # NettyServer, GameChannelInitializer, MessageDispatcher, ServerMetrics
│   │   ├── session/            # GameSession, SessionManager
│   │   └── dispatch/           # MsgController, MsgMapping, MsgControllerRegistry
│   └── proto/                  # MsgId.java (自动生成)
├── modules/                    # 业务模块
│   ├── user/                   # UserController (登录/登出)
│   └── inventory/              # 背包模块 (InventoryController, InventoryService, 物品模型, 效果处理器)
└── client/                     # GameClient (测试客户端)

src/main/proto/
├── game_message.proto      # GameMessage 统一包装器 + 心跳消息
├── user.proto              # 登录/登出业务消息
├── inventory.proto         # 背包模块消息
└── msg-modules.properties  # 消息号模块范围映射配置
```

## 快速入门

### 环境要求

- JDK 25+
- Gradle 9.5.1（项目自带 Gradle Wrapper）

### 1. 编译项目

```bash
./gradlew build
```

这会自动执行 `generateProto` → `generateMsgId` → `compileJava`，生成 Protobuf Java 类和 `MsgId.java`。

### 2. 启动服务器

```bash
./gradlew bootRun
```

服务器默认监听 `ws://localhost:8888/ws`，可通过 `src/main/resources/application.yml` 覆盖（均有代码默认值）：

```yaml
game:
  server:
    port: 8888
    max-connections: 10000
    rate-limit:
      per-second: 30
```

### 3. 运行测试客户端

项目未引入 Gradle `application` 插件，直接从 IDE 运行 `com.rainnov.client.GameClient` 的 `main` 方法即可（需要命令行启动时，先在 `build.gradle` 中添加 `application` 插件或注册 `JavaExec` 任务）。

客户端连接后自动每 30s 发送心跳，支持控制台命令：

```
login <token>   # 发送登录请求
logout          # 发送登出请求
quit            # 退出客户端
```

### 4. 编写业务模块

#### 4.1 定义 Proto 消息

在 `src/main/proto/` 下创建 `.proto` 文件，消息名遵循 `C{msgId}_{Name}` 命名规范：

```protobuf
// room.proto
syntax = "proto3";
package com.rainnov.framework.proto;
option java_package = "com.rainnov.framework.proto";
option java_outer_classname = "RoomProto";

message C2001_CreateRoomReq  { string room_name = 1; int32 max_players = 2; }
message C2002_CreateRoomResp { int64 room_id = 1; }
```

编译后 `MsgId.java` 会自动生成对应常量：

```java
public static final class ROOM {
    public static final int CREATE_ROOM_REQ = 2001;
    public static final int CREATE_ROOM_RESP = 2002;
}
```

#### 4.2 编写 Controller

```java
@MsgController
public class RoomController {

    @MsgMapping(value = MsgId.ROOM.CREATE_ROOM_REQ, requireAuth = true)
    public C2002_CreateRoomResp createRoom(GameSession session, C2001_CreateRoomReq req) {
        long roomId = // ... 业务逻辑
        return C2002_CreateRoomResp.newBuilder().setRoomId(roomId).build();
        // 框架自动包装为 GameMessage(msgId=2002) 发送给客户端
    }
}
```

方法签名固定为 `(GameSession session, XxxReq req)`，返回值为 proto Message 时框架自动发送响应（响应号 = 请求号 + 1），返回 `void` 则由业务自行调用 `session.send()`。

#### 4.3 跨用户共享状态的并发控制

框架只保证**单用户维度**串行：同一个 Session 的消息按到达顺序在专属虚拟线程上依次执行。队伍、公会、房间这类被多个用户同时改写的状态，由业务侧显式加锁，框架不提供隐式串行队列。

单进程内用按 key 的锁：

```java
private final ConcurrentHashMap<Long, ReentrantLock> teamLocks = new ConcurrentHashMap<>();

@MsgMapping(MsgId.GAME.TEAM_ACTION_REQ)
public void handleTeamAction(GameSession session, C3001_TeamActionReq req) {
    ReentrantLock lock = teamLocks.computeIfAbsent(req.getTeamId(), k -> new ReentrantLock());
    lock.lock();
    try {
        // 临界区：读改写队伍状态
    } finally {
        lock.unlock();
    }
}
```

多进程部署时改用分布式锁（Redis `SET NX PX` / Redisson / 数据库行锁等，按需引入依赖）。要点：

- 锁粒度对齐业务实体（`team:{teamId}`），不要用全局锁
- 必须设置过期时间，避免持锁进程崩溃后死锁
- 临界区内只做状态读改写，不要放 I/O 或阻塞等待
- 需要多把锁时固定加锁顺序，避免死锁

## 协议格式

所有消息通过 `GameMessage` 统一包装：

```protobuf
message GameMessage {
  int32 msg_id     = 1;  // 消息号，用于路由
  int32 seq        = 2;  // 序列号，请求/响应匹配
  bytes payload    = 3;  // 业务消息体
  int32 error_code = 4;  // 错误码，0 表示成功
}
```

### 消息号分段

| 范围        | 模块           |
|-------------|----------------|
| 1 ~ 999     | 系统（心跳等） |
| 1000 ~ 1999 | 登录           |
| 2000 ~ 2999 | 房间/匹配      |
| 3000 ~ 3999 | 游戏逻辑       |
| 4000 ~ 4999 | 社交           |
| 5000 ~ 5999 | 背包           |
| 30000+      | 管理/运维      |

模块范围在 `src/main/proto/msg-modules.properties` 中配置。

## 运行测试

```bash
./gradlew test
```

## 更多文档

详细设计、约定与开发指引见 [AGENTS.md](AGENTS.md) 索引与 `docs/` 目录：

- [docs/architecture.md](docs/architecture.md) — 框架架构与消息分发
- [docs/protocol.md](docs/protocol.md) — 协议与 msgId 生成机制
- [docs/conventions.md](docs/conventions.md) — 命名与编码约定
- [docs/guides/new-module.md](docs/guides/new-module.md) — 新业务模块脚手架

## License

Private
