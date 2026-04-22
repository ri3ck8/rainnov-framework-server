---
name: 创建模块 Controller
inclusion: manual
description: 为新业务模块创建 Controller 层消息处理器
---

# Skill: 创建模块 Controller

为新业务模块创建 `@MsgController` 消息处理器，将 Protobuf 请求路由到 Service 方法。

## 输入

用户需提供：
- 模块名称
- 需要处理的消息列表（对应 Proto 中定义的请求/响应）

## 前置条件

- 已完成 Proto 消息定义（create-proto-messages skill）
- 已完成 Service 层实现（create-module-service skill）

## 执行步骤

### 1. 创建 Controller 类

文件路径：`src/main/java/com/rainnov/modules/{模块名}/{Module}Controller.java`

参考 #[[file:src/main/java/com/rainnov/modules/inventory/InventoryController.java]] 和 #[[file:src/main/java/com/rainnov/modules/user/UserController.java]]：

```java
@MsgController
public class {Module}Controller {

    private final {Module}Service service;

    public {Module}Controller({Module}Service service) {
        this.service = service;
    }
}
```

### 2. 实现消息处理方法

每个处理方法遵循以下模式：

```java
@MsgMapping(MsgId.{MODULE}.{MSG_NAME}_REQ)
public C{msgId+1}_{Name}Resp handleXxx(GameSession session, C{msgId}_{Name}Req req) {
    // 1. 调用 Service 方法
    {Module}Service.XxxResult result = service.xxx(session, req.getParam1(), req.getParam2());
    
    // 2. 构建 Protobuf 响应
    C{msgId+1}_{Name}Resp.Builder builder = C{msgId+1}_{Name}Resp.newBuilder();
    // ... 填充字段
    return builder.build();
}
```

### 3. 关键约定

方法签名规范（参考 #[[file:src/main/java/com/rainnov/framework/net/dispatch/MsgMapping.java]]）：

- 第一个参数固定为 `GameSession session`
- 第二个参数为 Protobuf 请求消息类型
- 返回 Protobuf `Message` → 框架自动包装发送（响应消息号 = 请求消息号 + 1）
- 返回 `void` → 业务自行调用 `session.send()`

`@MsgMapping` 属性：
- `value` — 消息号，使用 `MsgId.{MODULE}.{MSG_NAME}` 常量
- `requireAuth` — 默认 `true`，登录等公开接口设为 `false`
- `groupBy` — 默认 `GroupType.USER`（按用户串行），可选 `TEAM`/`GUILD`/`TEAM_DISTRIBUTED`/`GUILD_DISTRIBUTED`

### 4. Helper 方法

如果多个处理方法需要将 Model 转换为 Proto 子消息，提取为 private helper 方法：

```java
private {Proto}SubMessage toProto{Name}({ModelSnapshot} snapshot) {
    return {Proto}SubMessage.newBuilder()
            .setField1(snapshot.field1())
            .setField2(snapshot.field2())
            .build();
}
```

## 注意事项

- Controller 只做请求解析和响应构建，业务逻辑全部委托给 Service
- 使用 `@MsgController` 注解（不是 Spring 的 `@Controller`）
- 构造器注入 Service（不使用 `@Autowired`）
- 导入 Proto 生成类时使用 `{Module}Proto.*` 通配导入
