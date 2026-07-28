# 指引：创建模块 Service

实现模块核心业务逻辑。

## 前置条件

- 已完成 Proto 消息定义（[proto-messages.md](proto-messages.md)）
- 已完成数据模型（[module-models.md](module-models.md)）

## 需要先确认的输入

- 模块名称
- 业务方法列表及逻辑描述
- 是否需要与其他模块交互（如背包的 `addItemAndNotify`）

## 1. Service 骨架

`src/main/java/com/rainnov/modules/{模块名}/{Module}Service.java`，参考 `modules/inventory/InventoryService.java`：

```java
@Slf4j
@Component
public class QuestService {

    private final QuestConfigRegistry configRegistry;
    private final ConcurrentHashMap<Long, PlayerQuests> dataStore = new ConcurrentHashMap<>();

    public QuestService(QuestConfigRegistry configRegistry) {
        this.configRegistry = configRegistry;
    }

    public PlayerQuests getOrCreate(long userId) {
        return dataStore.computeIfAbsent(userId, PlayerQuests::new);
    }
}
```

## 2. 业务方法

统一流程：

1. 从 `GameSession` 取 userId
2. `getOrCreate` 玩家数据
3. 参数校验，失败直接返回错误码
4. 执行业务逻辑
5. 返回 Result record

## 3. Result record

在 Service 内部定义返回类型，首字段为 `errorCode`：

```java
public record AcceptResult(int errorCode, int questId, int progress) {}
```

参考 `InventoryService` 中的 `QueryResult`、`AddResult`、`UseResult` 等。

## 4. 服务端主动推送（如需要）

```java
session.send(GameMessage.newBuilder()
        .setMsgId(MsgId.QUEST.QUEST_UPDATE_NOTIFY)
        .setPayload(notify.toByteString())
        .build());
```

## 5. 跨模块调用（如需要）

- 构造器注入其他模块的 Service
- 对外暴露语义化 public 方法（如 `addItemAndNotify`），内部完成数据变更 + 通知推送

## 注意事项

- `@Component` + `@Slf4j`，构造器注入
- 内存存储用 `ConcurrentHashMap`
- 业务失败返回 Result record 的错误码，不用异常传递
- 可能失败的外部调用（如效果执行）try-catch 包裹并记日志
- 消息号一律用 `MsgId.{MODULE}.{MSG_NAME}` 常量
