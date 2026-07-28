# 指引：创建模块 Controller

创建 `@MsgController` 消息处理器，把 Protobuf 请求路由到 Service。

## 前置条件

- 已完成 Proto 消息定义（[proto-messages.md](proto-messages.md)）
- 已完成 Service 层（[module-service.md](module-service.md)）

## 需要先确认的输入

- 模块名称
- 需要处理的消息列表（对应 Proto 中的请求/响应）

## 1. Controller 类

`src/main/java/com/rainnov/modules/{模块名}/{Module}Controller.java`，参考 `modules/inventory/InventoryController.java`、`modules/user/UserController.java`：

```java
@MsgController
public class QuestController {

    private final QuestService service;

    public QuestController(QuestService service) {
        this.service = service;
    }
}
```

## 2. 消息处理方法

```java
@MsgMapping(MsgId.QUEST.ACCEPT_QUEST_REQ)
public C6004_AcceptQuestResp acceptQuest(GameSession session, C6003_AcceptQuestReq req) {
    QuestService.AcceptResult result = service.accept(session, req.getQuestId());

    return C6004_AcceptQuestResp.newBuilder()
            .setErrorCode(result.errorCode())
            .setQuestId(result.questId())
            .build();
}
```

## 3. 关键约定

- 第一个参数固定 `GameSession session`，第二个为 Protobuf 请求类型
- 返回 Protobuf `Message` → 框架自动包装发送（响应号 = 请求号 + 1）
- 返回 `void` → 业务自行 `session.send()`
- `@MsgMapping` 属性：
  - `value` — 消息号，用 `MsgId.{MODULE}.{MSG_NAME}` 常量
  - `requireAuth` — 默认 `true`，登录等公开接口设 `false`
- 注解 msgId 必须与参数类名前缀 `C{msgId}_` 一致，否则启动 Fail-Fast

## 4. Proto 转换 helper

多个方法复用的 Model → Proto 转换提取为 private 方法：

```java
private QuestEntry toProtoQuest(QuestSnapshot snapshot) {
    return QuestEntry.newBuilder()
            .setQuestId(snapshot.questId())
            .setProgress(snapshot.progress())
            .build();
}
```

## 注意事项

- Controller 只做请求解析与响应构建，业务逻辑全部委托 Service
- 使用框架的 `@MsgController`，不是 Spring 的 `@Controller`
- 构造器注入，不用 `@Autowired`
- Proto 生成类通过 `{Module}Proto.*` 导入
