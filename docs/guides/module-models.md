# 指引：创建模块数据模型

为新业务模块创建 Model 层：数据模型、枚举、配置类、错误码。

## 需要先确认的输入

- 模块名称（如 quest、shop、mail）
- 核心业务实体描述（如"任务有 ID、名称、类型、状态、进度、奖励"）
- 需要的枚举类型（如任务类型、任务状态）
- 是否需要配置注册中心（类似 `ItemConfigRegistry`）

## 1. 创建包结构

在 `src/main/java/com/rainnov/modules/{模块名}/` 下建立：

- `model/` — 数据模型、枚举、record
- `config/` — 配置注册中心（如需要）

## 2. 枚举类

放 `model/` 包，参考 `modules/inventory/model/ItemType.java`、`ExpirationMode.java`。

## 3. 配置 record（不可变）

```java
public record QuestConfig(
        int questId,
        String name,
        QuestType type,
        int targetCount,
        /** 可为 null：无奖励配置 */
        RewardConfig reward
) {}
```

用 record 而非 class；nullable 字段在 javadoc 注明。参考 `model/ItemConfig.java`。

## 4. 运行时数据模型（可变）

用 Lombok 的可变类，参考 `model/Slot.java`、`model/PlayerInventory.java`：

```java
@Getter
@Setter
public class PlayerQuests {
    private final long userId;
    private final Map<Integer, QuestState> quests = new HashMap<>();

    public PlayerQuests(long userId) { this.userId = userId; }
}
```

## 5. 快照 record（如需要）

返回给 Controller 层的只读视图，参考 `model/SlotSnapshot.java`。

## 6. 错误码常量类

放模块根包，参考 `modules/inventory/InventoryErrorCode.java`：

```java
public final class QuestErrorCode {
    private QuestErrorCode() {}

    public static final int SUCCESS = 0;
    public static final int QUEST_NOT_FOUND = 6001;   // 建议以模块消息号范围为前缀
}
```

## 7. 配置注册中心（如需要）

```java
@Slf4j
@Component
public class QuestConfigRegistry {

    private final Map<Integer, QuestConfig> configs = new HashMap<>();

    public void register(QuestConfig config) { configs.put(config.questId(), config); }

    public QuestConfig getConfig(int questId) { return configs.get(questId); }
}
```

参考 `modules/inventory/config/ItemConfigRegistry.java`；可选实现启动时配置校验（非法配置记 WARN 或 Fail-Fast）。

## 注意事项

- 配置对象用 record（不可变），运行时状态用 class（可变）
- 用 Lombok 减少样板代码
- 错误码 `SUCCESS` 固定为 0
