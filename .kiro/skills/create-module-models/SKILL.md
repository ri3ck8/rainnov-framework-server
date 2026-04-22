---
name: 创建模块数据模型
inclusion: manual
description: 为新业务模块创建数据模型、枚举、配置类和错误码
---

# Skill: 创建模块数据模型

为新业务模块创建 Model 层，包括数据模型、枚举、配置类和错误码常量。

## 输入

用户需提供：
- 模块名称（如 quest、shop、mail）
- 核心业务实体描述（如"任务有 ID、名称、类型、状态、进度、奖励"）
- 需要的枚举类型（如任务类型、任务状态）
- 是否需要配置注册中心（类似 ItemConfigRegistry）

## 执行步骤

### 1. 创建包结构

在 `src/main/java/com/rainnov/modules/{模块名}/` 下创建：
- `model/` — 数据模型、枚举、Record
- `config/` — 配置注册中心（如需要）

### 2. 创建枚举类

放在 `model/` 包下，参考：

```java
// #[[file:src/main/java/com/rainnov/modules/inventory/model/ItemType.java]]
// #[[file:src/main/java/com/rainnov/modules/inventory/model/ExpirationMode.java]]
```

### 3. 创建配置 Record

使用 Java Record 定义不可变配置对象，参考：
```java
// #[[file:src/main/java/com/rainnov/modules/inventory/model/ItemConfig.java]]
```

- 使用 record 而非 class
- nullable 字段在 javadoc 中注明

### 4. 创建运行时数据模型

使用 Lombok `@Getter` `@Setter` 的可变类，参考：
```java
// #[[file:src/main/java/com/rainnov/modules/inventory/model/Slot.java]]
// #[[file:src/main/java/com/rainnov/modules/inventory/model/PlayerInventory.java]]
```

### 5. 创建快照 Record（如需要）

用于返回数据快照给 Controller 层，参考：
```java
// #[[file:src/main/java/com/rainnov/modules/inventory/model/SlotSnapshot.java]]
```

### 6. 创建错误码常量类

放在模块根包下，参考 #[[file:src/main/java/com/rainnov/modules/inventory/InventoryErrorCode.java]]：

```java
public final class {Module}ErrorCode {
    private {Module}ErrorCode() {}
    public static final int SUCCESS = 0;
    // 模块特定错误码，建议使用模块消息号范围作为前缀
}
```

### 7. 创建配置注册中心（如需要）

注册为 Spring `@Component`，参考 #[[file:src/main/java/com/rainnov/modules/inventory/config/ItemConfigRegistry.java]]：

- 提供 `register()` 和 `getConfig()` 方法
- 使用 `HashMap` 存储
- 可选：实现启动校验逻辑

## 注意事项

- 配置对象用 record（不可变），运行时状态用 class（可变）
- 使用 Lombok 减少样板代码
- 错误码 SUCCESS 固定为 0
