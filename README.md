# Rainnov Framework Server

基于 **Netty + Protobuf + Spring Boot 4.0 + Java 21** 的休闲游戏服务器框架。

通过消息号（Message ID）将网络消息路由到对应的业务处理器，支持注解驱动开发，业务开发者只需关注业务逻辑。

## 特性

- WebSocket + Protobuf 二进制协议，高性能异步非阻塞 I/O
- `@MsgController` + `@MsgMapping` 注解自动注册消息处理器，类似 Spring MVC 的开发体验
- 每用户独立消息队列 + Java 21 虚拟线程，天然保证单用户消息串行
- 支持按队伍/公会维度的共享队列串行消费（单进程 & 跨进程 Redis 分布式队列）
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
│   │   ├── queue/              # SharedQueueManager, DistributedQueueManager, GroupMessage, GroupType, GroupKeyResolver, GameGroupKeyResolver
│   │   └── dispatch/           # MsgController, MsgMapping, MsgControllerRegistry
│   └── proto/                  # MsgId.java (自动生成)
├── modules/                    # 业务模块
│   ├── user/                   # UserController (登录/登出)
│   └── inventory/              # 背包模块 (InventoryController, InventoryService, 物品模型, 效果处理器)
└── client/                     # GameClient (测试客户端)

src/main/proto/
├── game_message.proto      # GameMessage 统一包装器 + 心跳消息
├── user.proto              # 登录/登出业务消息
└── msg-modules.properties  # 消息号模块范围映射配置
```

## 快速入门

### 环境要求

- JDK 21+
- Gradle 9.x（项目自带 Gradle Wrapper）
- Redis（仅使用分布式队列时需要）

### 1. 编译项目

```bash
./gradlew build
```

这会自动执行 `generateProto` → `generateMsgId` → `compileJava`，生成 Protobuf Java 类和 `MsgId.java`。

### 2. 启动服务器

```bash
./gradlew bootRun
```

服务器默认监听 `ws://localhost:8888/ws`，可通过 `application.properties` 修改：

```properties
game.server.port=8888
game.server.max-connections=10000
game.session.rate-limit=30.0
```

### 3. 运行测试客户端

```bash
./gradlew run -PmainClass=com.rainnov.client.GameClient
```

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

#### 4.3 队伍/公会串行消费

对需要按组串行的消息，使用 `groupBy` 属性：

```java
// 同一 teamId 的消息在单进程内串行执行
@MsgMapping(value = MsgId.GAME.TEAM_ACTION_REQ, groupBy = GroupType.TEAM)
public void handleTeamAction(GameSession session, C3001_TeamActionReq req) { ... }

// 跨进程串行（通过 Redis 分布式队列）
@MsgMapping(value = MsgId.GAME.CROSS_SERVER_REQ, groupBy = GroupType.TEAM_DISTRIBUTED)
public void handleCrossServer(GameSession session, C3002_CrossServerReq req) { ... }
```

需要实现 `GroupKeyResolver` 接口提供 groupKey 解析逻辑，参考 `GameGroupKeyResolver` 示例。

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
| 30000+      | 管理/运维      |

模块范围在 `src/main/proto/msg-modules.properties` 中配置。

## 运行测试

```bash
./gradlew test
```

## License

Private
