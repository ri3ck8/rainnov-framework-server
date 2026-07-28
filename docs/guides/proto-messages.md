# 指引：创建 Proto 消息定义

为新业务模块定义 Protobuf 消息并注册消息号范围。

## 需要先确认的输入

- 模块名称（英文大写，如 QUEST、SHOP、MAIL）
- 消息号起始范围（如 6000）
- 请求/响应消息列表（功能描述即可）

## 1. 注册消息号范围

编辑 `src/main/proto/msg-modules.properties`，追加 `{起始消息号}={模块名大写}`：

```properties
1=SYSTEM
1000=LOGIN
5000=INVENTORY
6000=QUEST
```

范围不得与已有模块重叠。当前分段见 [../protocol.md](../protocol.md#消息号分段)。

## 2. 创建 Proto 文件

新建 `src/main/proto/{模块名小写}.proto`：

```protobuf
syntax = "proto3";
package com.rainnov.framework.proto;
option java_package = "com.rainnov.framework.proto";
option java_outer_classname = "QuestProto";

// 公共子消息（非 C 前缀）放在文件顶部
message QuestEntry {
  int32 quest_id = 1;
  int32 progress = 2;
}

message C6001_QueryQuestListReq {}
message C6002_QueryQuestListResp {
  repeated QuestEntry quests = 1;
}
```

必须遵循：

- 消息命名 `C{msgId}_{MessageName}`
- 请求消息号为奇数，响应 = 请求 + 1（6001 / 6002）
- 服务端主动推送紧跟在请求/响应对之后
- 参考 `src/main/proto/inventory.proto` 的组织方式

## 3. 生成代码

```bash
./gradlew generateProto generateMsgId
```

## 4. 验证

确认 `src/main/java/com/rainnov/framework/proto/MsgId.java` 中生成了新模块嵌套类及全部常量。该文件自动生成，禁止手动编辑。

生成机制细节见 [../protocol.md](../protocol.md#msgidjava-生成机制)。
