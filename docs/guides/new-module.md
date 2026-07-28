# 指引：新模块完整脚手架

端到端创建一个业务模块。本指引编排其余各指引，按 Phase 顺序执行，每个 Phase 完成后先编译验证再继续。

## 需要先确认的输入

- 模块名称（英文，如 quest、shop、mail）
- 模块功能描述
- 消息号起始范围（如 6000）
- 核心实体与枚举描述
- 请求/响应消息列表

## Phase 1 — 协议层

按 [proto-messages.md](proto-messages.md)：

1. 在 `src/main/proto/msg-modules.properties` 注册消息号范围
2. 创建 `src/main/proto/{module}.proto`
3. `./gradlew generateProto generateMsgId`
4. 确认 `MsgId.java` 中生成了新模块嵌套类

## Phase 2 — 模型层

按 [module-models.md](module-models.md)：枚举 → 配置 record → 运行时模型 → 快照 record → 错误码 → 配置注册中心。

## Phase 3 — Handler 框架（可选）

若模块需要按类型分派逻辑，按 [handler-strategy.md](handler-strategy.md) 创建 Context、Handler 接口、Registry、示例实现。不需要策略分派则跳过。

## Phase 4 — Service 层

按 [module-service.md](module-service.md)：Service 骨架 → 业务方法 → Result record。

## Phase 5 — Controller 层

按 [module-controller.md](module-controller.md)：Controller → 消息处理方法 → Proto 转换 helper。

## Phase 6 — 验证

```bash
./gradlew compileJava
./gradlew test
```

## 生成的文件结构

```
src/main/proto/{module}.proto                       # Proto 消息定义
src/main/java/com/rainnov/modules/{module}/
├── {Module}Controller.java                         # 消息处理器
├── {Module}Service.java                            # 核心业务逻辑
├── {Module}ErrorCode.java                          # 错误码常量
├── config/
│   └── {Module}ConfigRegistry.java                 # 配置注册中心
├── model/
│   ├── {Entity}.java                               # 运行时数据模型
│   ├── {Entity}Config.java                         # 配置 record
│   ├── {Entity}Snapshot.java                        # 快照 record
│   └── {Type}Type.java                             # 类型枚举
└── {handler_pkg}/                                  # 可选
    ├── {Name}Handler.java
    ├── {Name}HandlerRegistry.java
    ├── {Name}Context.java
    └── {Example}Handler.java
```

## 注意事项

- 遵循现有 inventory 模块的风格与命名，参考 [../modules/inventory.md](../modules/inventory.md)
- `MsgId.java` 禁止手动编辑，只能通过 `./gradlew generateMsgId` 生成
- 所有 Spring Bean 构造器注入
- 通用约定见 [../conventions.md](../conventions.md)
