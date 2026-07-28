# 指引：策略模式 Handler 框架

当模块需要按类型分派不同处理逻辑时（物品效果、任务条件检查、奖励发放等），创建 Handler 接口 + Registry 注册中心。不需要策略分派的模块跳过本指引。

## 需要先确认的输入

- 模块名称
- Handler 职责描述（如"任务条件检查器"、"奖励发放处理器"）
- 分派维度的枚举类型（如 `QuestType`、`RewardType`）

## 1. Context record

`src/main/java/com/rainnov/modules/{模块名}/{子包}/{Name}Context.java`，参考 `modules/inventory/effect/ItemEffectContext.java`：

```java
public record QuestCheckContext(
        GameSession session,
        int questId,
        QuestConfig config,
        int progress
) {}
```

## 2. Handler 接口

参考 `modules/inventory/effect/ItemEffectHandler.java`：

```java
public interface QuestCheckHandler {

    /** 声明处理的类型 */
    QuestType getType();

    /** 执行处理逻辑 */
    void handle(QuestCheckContext context);
}
```

## 3. Registry 注册中心

参考 `modules/inventory/effect/ItemEffectHandlerRegistry.java`：

```java
@Slf4j
@Component
public class QuestCheckHandlerRegistry implements InitializingBean {

    private final List<QuestCheckHandler> handlers;
    private final Map<QuestType, QuestCheckHandler> handlerMap = new EnumMap<>(QuestType.class);

    public QuestCheckHandlerRegistry(List<QuestCheckHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void afterPropertiesSet() {
        for (QuestCheckHandler handler : handlers) {
            QuestType type = handler.getType();
            if (handlerMap.containsKey(type)) {
                throw new IllegalStateException("Duplicate QuestCheckHandler for type: " + type);
            }
            handlerMap.put(type, handler);
            log.info("Registered QuestCheckHandler for {}: {}", type, handler.getClass().getSimpleName());
        }
    }

    public QuestCheckHandler getHandler(QuestType type) {
        return handlerMap.get(type);
    }
}
```

## 4. Handler 实现

参考 `modules/inventory/effect/HealingEffectHandler.java`：

```java
@Slf4j
@Component
public class DailyQuestCheckHandler implements QuestCheckHandler {

    @Override
    public QuestType getType() {
        return QuestType.DAILY;
    }

    @Override
    public void handle(QuestCheckContext context) {
        log.info("处理 {} 类型: userId={}", getType(), context.session().getUserId());
    }
}
```

## 注意事项

- Handler 实现类必须是 Spring `@Component`，Registry 通过注入 `List<Handler>` 自动收集
- 同一类型重复注册抛 `IllegalStateException`（启动期 Fail-Fast）
- 用 `EnumMap` 提高查找性能
- `getHandler()` 未命中返回 `null`，由调用方决定策略（背包模块的做法是返回 `EFFECT_HANDLER_NOT_FOUND` 且不扣减物品）
