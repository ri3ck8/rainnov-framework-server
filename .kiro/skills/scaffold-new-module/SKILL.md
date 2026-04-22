---
name: 新模块完整脚手架
inclusion: manual
description: 端到端脚手架 — 一次性创建完整业务模块的所有文件
---

# Skill: 新模块完整脚手架

一次性创建完整业务模块的所有层次文件。这是一个编排 Skill，按顺序调用其他 Skill 的步骤。

## 输入

用户需提供：
- 模块名称（英文，如 quest、shop、mail）
- 模块功能描述
- 消息号起始范围
- 核心实体和枚举描述
- 请求/响应消息列表

## 完整流程

按以下顺序执行，每步完成后验证再进入下一步：

### Phase 1: 协议层

1. 使用 `create-proto-messages` skill 的步骤：
   - 注册消息号范围到 `msg-modules.properties`
   - 创建 `.proto` 文件
   - 运行 `./gradlew generateProto generateMsgId`
   - 验证 MsgId.java 生成正确

### Phase 2: 模型层

2. 使用 `create-module-models` skill 的步骤：
   - 创建枚举类
   - 创建配置 Record
   - 创建运行时数据模型
   - 创建快照 Record
   - 创建错误码常量类
   - 创建配置注册中心

### Phase 3: Handler 框架（可选）

3. 如果模块需要策略分派，使用 `create-module-handler-framework` skill：
   - 创建 Context、Handler 接口、Registry、示例实现

### Phase 4: Service 层

4. 使用 `create-module-service` skill 的步骤：
   - 创建 Service 骨架
   - 实现业务方法
   - 定义 Result Record

### Phase 5: Controller 层

5. 使用 `create-module-controller` skill 的步骤：
   - 创建 Controller
   - 实现消息处理方法
   - 提取 Helper 方法

### Phase 6: 验证

6. 运行构建验证：
   ```bash
   ./gradlew compileJava
   ```

## 生成的文件结构

```
src/main/proto/{module}.proto                              # Proto 消息定义
src/main/java/com/rainnov/modules/{module}/
├── {Module}Controller.java                                # 消息处理器
├── {Module}Service.java                                   # 核心业务逻辑
├── {Module}ErrorCode.java                                 # 错误码常量
├── config/
│   └── {Module}ConfigRegistry.java                        # 配置注册中心
├── model/
│   ├── {Entity}.java                                      # 运行时数据模型
│   ├── {Entity}Config.java                                # 配置 Record
│   ├── {Entity}Snapshot.java                              # 快照 Record
│   ├── {Type}Type.java                                    # 类型枚举
│   └── ...                                                # 其他模型类
└── {handler_pkg}/  (可选)
    ├── {Name}Handler.java                                 # Handler 接口
    ├── {Name}HandlerRegistry.java                         # Handler 注册中心
    ├── {Name}Context.java                                 # Handler 上下文
    └── {Example}Handler.java                              # 示例 Handler
```

## 注意事项

- 每个 Phase 完成后先编译验证，确保无错误再继续
- 遵循现有模块（inventory）的代码风格和命名约定
- MsgId.java 禁止手动编辑，只能通过 `./gradlew generateMsgId` 生成
- 所有 Spring Bean 使用构造器注入
- 参考 #[[file:.kiro/steering/structure.md]] 中的关键约定
