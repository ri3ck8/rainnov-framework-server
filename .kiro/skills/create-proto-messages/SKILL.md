---
name: 创建 Proto 消息定义
inclusion: manual
description: 为新业务模块定义 Protobuf 消息并注册消息号范围
---

# Skill: 创建 Proto 消息定义

为新业务模块创建 Protobuf 消息文件并注册到消息号映射。

## 输入

用户需提供：
- 模块名称（英文，如 QUEST、SHOP、MAIL）
- 消息号起始范围（如 6000）
- 需要的请求/响应消息列表（功能描述即可）

## 执行步骤

### 1. 注册消息号范围

编辑 `src/main/proto/msg-modules.properties`，添加新模块的消息号范围映射。

格式：`{起始消息号}={模块名大写}`

参考已有配置：
```properties
1=SYSTEM
1000=LOGIN
5000=INVENTORY
```

### 2. 创建 Proto 文件

在 `src/main/proto/` 下创建 `{模块名小写}.proto` 文件。

必须遵循的规范：
- `syntax = "proto3";`
- `package com.rainnov.framework.proto;`
- `option java_package = "com.rainnov.framework.proto";`
- `option java_outer_classname = "{ModuleName}Proto";`
- 消息命名：`C{msgId}_{MessageName}`，如 `C6001_QueryQuestListReq`
- 请求消息号为奇数起始，响应消息号 = 请求消息号 + 1
- 服务端主动推送消息号紧跟在请求/响应对之后
- 公共子消息（非 C 前缀）放在文件顶部

参考 #[[file:src/main/proto/inventory.proto]] 的结构。

### 3. 生成代码

运行以下命令生成 Protobuf Java 代码和 MsgId 常量：

```bash
./gradlew generateProto generateMsgId
```

### 4. 验证

确认 `MsgId.java` 中生成了新模块的内部类及所有消息常量（该文件自动生成，禁止手动编辑）。

## 注意事项

- MsgId.java 是自动生成的，禁止手动编辑
- 消息号范围不能与已有模块重叠
- 每对请求/响应消息号必须连续（如 6001/6002）
