---
name: 创建 Handler 框架
inclusion: manual
description: 为新业务模块创建策略模式的 Handler 接口和注册中心
---

# Skill: 创建 Handler 框架（策略模式）

当模块需要按类型分派不同处理逻辑时（如物品效果、任务条件检查、奖励发放），创建 Handler 接口 + Registry 注册中心。

## 输入

用户需提供：
- 模块名称
- Handler 的职责描述（如"任务条件检查器"、"奖励发放处理器"）
- 分派维度的枚举类型（如 QuestType、RewardType）

## 适用场景

- 模块内有多种类型需要不同处理逻辑
- 需要支持扩展新类型而不修改核心代码
- 类似 inventory 模块的 ItemEffectHandler 模式

如果模块不需要策略分派，跳过此 Skill。

## 执行步骤

### 1. 创建 Context Record

文件路径：`src/main/java/com/rainnov/modules/{模块名}/{子包}/{Name}Context.java`

参考 #[[file:src/main/java/com/rainnov/modules/inventory/effect/ItemEffectContext.java]]：

```java
public record {Name}Context(
    GameSession session,
    // 业务相关字段
) {}
```

### 2. 创建 Handler 接口

文件路径：`src/main/java/com/rainnov/modules/{模块名}/{子包}/{Name}Handler.java`

参考 #[[file:src/main/java/com/rainnov/modules/inventory/effect/ItemEffectHandler.java]]：

```java
public interface {Name}Handler {
    /** 声明处理的类型 */
    {EnumType} getType();
    
    /** 执行处理逻辑 */
    void handle({Name}Context context);  // 或返回具体结果类型
}
```

### 3. 创建 Registry 注册中心

文件路径：`src/main/java/com/rainnov/modules/{模块名}/{子包}/{Name}HandlerRegistry.java`

参考 #[[file:src/main/java/com/rainnov/modules/inventory/effect/ItemEffectHandlerRegistry.java]]：

```java
@Slf4j
@Component
public class {Name}HandlerRegistry implements InitializingBean {

    private final List<{Name}Handler> handlers;
    private final Map<{EnumType}, {Name}Handler> handlerMap = new EnumMap<>({EnumType}.class);

    public {Name}HandlerRegistry(List<{Name}Handler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void afterPropertiesSet() {
        for ({Name}Handler handler : handlers) {
            {EnumType} type = handler.getType();
            if (handlerMap.containsKey(type)) {
                throw new IllegalStateException(
                    "Duplicate {Name}Handler for type: " + type);
            }
            handlerMap.put(type, handler);
            log.info("Registered {Name}Handler for {}: {}", type, handler.getClass().getSimpleName());
        }
    }

    public {Name}Handler getHandler({EnumType} type) {
        return handlerMap.get(type);
    }
}
```

### 4. 创建示例 Handler 实现

```java
@Slf4j
@Component
public class {Example}Handler implements {Name}Handler {

    @Override
    public {EnumType} getType() {
        return {EnumType}.{VALUE};
    }

    @Override
    public void handle({Name}Context context) {
        // 示例实现
        log.info("处理 {} 类型: userId={}", getType(), context.session().getUserId());
    }
}
```

参考 #[[file:src/main/java/com/rainnov/modules/inventory/effect/HealingEffectHandler.java]]

## 注意事项

- Handler 实现类必须注册为 Spring `@Component`
- Registry 通过 Spring 自动注入 `List<Handler>` 收集所有实现
- 同一类型注册多个 Handler 时抛出 `IllegalStateException`
- 使用 `EnumMap` 提高查找性能
- Handler 不存在时 `getHandler()` 返回 null，由调用方决定处理策略
