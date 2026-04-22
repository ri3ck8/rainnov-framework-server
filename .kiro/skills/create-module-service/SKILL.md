---
name: 创建模块 Service
inclusion: manual
description: 为新业务模块创建 Service 层核心业务逻辑
---

# Skill: 创建模块 Service

为新业务模块创建 Service 组件，实现核心业务逻辑。

## 输入

用户需提供：
- 模块名称
- 业务方法列表及逻辑描述
- 是否需要与其他模块交互（如背包模块的 addItemAndNotify）

## 前置条件

- 已完成 Proto 消息定义（create-proto-messages skill）
- 已完成数据模型创建（create-module-models skill）

## 执行步骤

### 1. 创建 Service 类骨架

文件路径：`src/main/java/com/rainnov/modules/{模块名}/{Module}Service.java`

参考 #[[file:src/main/java/com/rainnov/modules/inventory/InventoryService.java]] 的结构：

```java
@Slf4j
@Component
public class {Module}Service {

    // 注入依赖（配置注册中心、其他 Service 等）
    private final {Module}ConfigRegistry configRegistry;
    
    // 内存存储：ConcurrentHashMap<Long, PlayerXxx>
    private final ConcurrentHashMap<Long, Player{Module}> dataStore = new ConcurrentHashMap<>();

    // 构造器注入
    public {Module}Service({Module}ConfigRegistry configRegistry) {
        this.configRegistry = configRegistry;
    }

    // getOrCreate 方法
    public Player{Module} getOrCreate(long userId) {
        return dataStore.computeIfAbsent(userId, id -> new Player{Module}(id));
    }
}
```

### 2. 实现业务方法

每个业务方法遵循以下模式：

1. 从 GameSession 获取 userId
2. 获取或创建玩家数据
3. 参数校验（返回错误码）
4. 执行业务逻辑
5. 返回 Result record

### 3. 定义 Result Record

在 Service 类内部定义返回值 Record：

```java
public record XxxResult(int errorCode, /* 业务数据字段 */) {}
```

参考 InventoryService 中的 QueryResult、AddResult、UseResult 等。

### 4. 服务端主动推送（如需要）

通过 `GameSession.send()` 构建 GameMessage 推送：

```java
session.send(GameMessage.newBuilder()
    .setMsgId(MsgId.{MODULE}.{NOTIFY_MSG_ID})
    .setPayload(notifyBuilder.build().toByteString())
    .build());
```

### 5. 跨模块调用（如需要）

- 通过构造器注入其他模块的 Service
- 提供 public 方法供其他模块调用（如 `addItemAndNotify`）

## 注意事项

- Service 注册为 Spring `@Component`
- 使用 `@Slf4j` 记录日志
- 内存存储使用 `ConcurrentHashMap`
- 方法返回 Result record 而非直接抛异常
- 异常处理：效果执行等可能失败的操作用 try-catch 包裹并记录日志
- 所有 Proto 类的引用使用 `MsgId.{MODULE}.{MSG_NAME}` 常量
