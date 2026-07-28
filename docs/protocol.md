# 协议与 MsgId 生成

## GameMessage 统一包装器

```protobuf
// src/main/proto/game_message.proto
syntax = "proto3";
package com.rainnov.framework.proto;

message GameMessage {
  int32 msg_id     = 1;  // 消息号，路由键（业务层约束为 short 范围 -32768~32767）
  int32 seq        = 2;  // 序列号，请求/响应匹配
  bytes payload    = 3;  // 业务 proto 序列化字节
  int32 error_code = 4;  // 0 = 成功
}

// 心跳（系统消息），同样遵循 C{msgId}_ 命名，落在 SYSTEM 段
message C1_HeartbeatReq  { int64 timestamp = 1; }
message C2_HeartbeatResp { int64 timestamp = 1; }
```

心跳由 `MessageDispatcher` 在 IO 线程直接短路回复（`MsgId.SYSTEM.HEARTBEAT_REQ` = 1 → `HEARTBEAT_RESP` = 2），不进入用户业务队列；payload 解析失败回 `error_code=400`。

| 字段 | 类型 | 说明 |
|---|---|---|
| msg_id | int32 | 路由键 |
| seq | int32 | 客户端自增，响应回填请求 seq |
| payload | bytes | 业务消息体 |
| error_code | int32 | 0 成功，401/404/400/500 见 [architecture.md](architecture.md#错误处理) |

## 业务消息命名规范

所有业务 message 必须命名为 `C{msgId}_{MessageName}`，框架从类名前缀推导 msgId，无需额外配置。

| 消息名 | msgId | 说明 |
|---|---|---|
| `C1001_LoginReq` | 1001 | 登录请求 |
| `C1002_LoginResp` | 1002 | 登录响应 |
| `C1003_LogoutReq` | 1003 | 登出请求 |
| `C5015_InventoryChangeNotify` | 5015 | 服务端主动推送 |

规则：

- 请求消息号为奇数，响应 = 请求 + 1
- 服务端主动推送紧跟在请求/响应对之后
- 公共子消息（如 `InventorySlot`）不加 `C` 前缀，放在文件顶部
- 每个 proto 文件设置 `java_outer_classname = "{ModuleName}Proto"`

## 消息号分段

| 范围 | 模块（`msg-modules.properties`） |
|---|---|
| 1 ~ 999 | SYSTEM（心跳、握手、错误通知） |
| 1000 ~ 1999 | LOGIN |
| 2000 ~ 2999 | ROOM |
| 3000 ~ 3999 | GAME |
| 4000 ~ 4999 | SOCIAL |
| 5000 ~ 5999 | INVENTORY |
| 30000+ | ADMIN（管理/运维） |

配置文件当前内容：

```properties
# 格式：{msgId范围起始值}={模块名}
# msgId 落在 [起始值, 下一个起始值) 时使用该模块名
1=SYSTEM
1000=LOGIN
2000=ROOM
3000=GAME
4000=SOCIAL
5000=INVENTORY
30000=ADMIN
```

新增模块时在此追加一行，范围不得与已有模块重叠。

## MsgId.java 生成机制

`MsgId.java` 由 `build.gradle` 中的 `generateMsgId` 任务生成，**禁止手动编辑**。

- 输入：`src/main/proto/**/*.proto` + `src/main/proto/msg-modules.properties`
- 输出：`src/main/java/com/rainnov/framework/proto/MsgId.java`
- 任务链：`generateMsgId.dependsOn generateProto`，`compileJava.dependsOn generateMsgId`

流程：

1. 读取 properties，构建范围 → 模块名映射（起始值降序便于查找）
2. 正则 `message\s+C(\d+)_(\w+)` 扫描所有 `.proto`，提取 msgId 与消息名
3. 按范围归类到模块；不在任何范围内时回退为 `MODULE_{base}`（base = msgId 向下取整到千位）
4. 生成按模块嵌套的静态类，常量名由 CamelCase 转 UPPER_SNAKE_CASE（`LoginReq` → `LOGIN_REQ`）

生成结果形如：

```java
// 由 generateMsgId 任务自动生成，请勿手动编辑。
package com.rainnov.framework.proto;

public final class MsgId {

    private MsgId() {}

    public static final class LOGIN {
        public static final int LOGIN_REQ  = 1001;
        public static final int LOGIN_RESP = 1002;
        public static final int LOGOUT_REQ = 1003;
    }

    public static final class INVENTORY {
        public static final int QUERY_INVENTORY_REQ = 5001;
        // ...
    }
}
```

业务代码统一引用 `MsgId.{MODULE}.{MSG_NAME}` 常量，不写裸数字。
