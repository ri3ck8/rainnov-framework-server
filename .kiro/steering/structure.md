# 项目结构与架构

## 包结构

```
com.rainnov/
├── framework/                         # 框架核心（不放业务逻辑）
│   ├── net/
│   │   ├── dispatch/                  # 注解驱动的消息路由
│   │   │   ├── MsgController.java        # @MsgController — 标记消息处理类（也是 Spring @Component）
│   │   │   ├── MsgMapping.java           # @MsgMapping — 将 msgId 映射到方法，支持 groupBy / requireAuth
│   │   │   └── MsgControllerRegistry.java # 启动时扫描 Bean，构建 msgId → MethodInvoker 映射
│   │   ├── server/                    # Netty 服务器基础设施
│   │   ├── session/                   # 客户端会话管理（GameSession / SessionManager）
│   │   └── queue/                     # 基于分组的串行消息消费（Shared / Distributed）
│   └── proto/                         # 自动生成（MsgId.java — 禁止编辑）
├── modules/                           # 业务模块（每个模块一个子包）
│   ├── user/                          # 登录/登出
│   └── inventory/                     # 背包系统
│       ├── config/                    # 配置注册中心
│       ├── model/                     # 数据模型（record / POJO）
│       └── effect/                    # 物品效果处理器（策略模式）
└── client/                            # 测试用 WebSocket 客户端
```

## Proto 源文件

```
src/main/proto/
├── game_message.proto      # GameMessage 统一包装器 + 心跳消息
├── user.proto              # 登录/登出消息
├── inventory.proto         # 背包模块消息
└── msg-modules.properties  # 消息号范围 → 模块名映射（1000=LOGIN, 5000=INVENTORY 等）
```

## 关键约定

### Proto 消息命名
- 消息名格式：`C{msgId}_{MessageName}`（如 `C1001_LoginReq`）。`generateMsgId` 任务依赖此命名。
- 请求消息号为奇数，响应 = 请求 + 1（如 Req=5001, Resp=5002）。
- 服务端主动推送消息号也遵循 `C{msgId}_` 前缀（如 `C5015_InventoryChangeNotify`）。
- Proto 文件的 `java_outer_classname` 按模块命名（如 `InventoryProto`、`LoginProto`）。

### Controller 方法签名
- 方法签名：`(GameSession session, XxxReq req)` — 第一个参数固定 `GameSession`，第二个为 Protobuf 请求类型。
- 返回 Protobuf `Message` → 框架自动包装为 `GameMessage`（msgId = req + 1）发送。
- 返回 `void` → 业务自行调用 `session.send()`。
- `@MsgMapping(requireAuth = false)` 用于无需鉴权的接口（如登录）。默认 `true`。
- `@MsgMapping` 的 `groupBy` 控制队列路由：`USER`（默认）、`TEAM`/`GUILD`、`TEAM_DISTRIBUTED`/`GUILD_DISTRIBUTED`。

### 模块结构约定
- 每个业务模块放在 `com.rainnov.modules.{moduleName}/` 下。
- 标准子包：`model/`（数据模型）、`config/`（配置注册）、`effect/`（效果处理器，如有）。
- Controller 类：`{Module}Controller.java`，标注 `@MsgController`，构造器注入 Service。
- Service 类：`{Module}Service.java`，标注 `@Component`，包含核心业务逻辑。
- ErrorCode 类：`{Module}ErrorCode.java`，`public static final int` 常量，`SUCCESS = 0`。
- 方法返回值使用 Java `record` 作为结果类型（嵌套在 Service 内部）。

### 效果处理器模式（策略模式）
- 接口：`ItemEffectHandler`（声明 `getItemType()` + `handle(context)`）。
- 注册中心：`ItemEffectHandlerRegistry`（`InitializingBean`，自动收集所有 `@Component` Handler）。
- 每个 Handler 是 `@Component`，通过 Spring 自动注入到 Registry。

### 测试约定
- 测试目录镜像 main 包结构：`src/test/java/com/rainnov/...`
- 使用 JUnit 5 + Mockito（`@ExtendWith(MockitoExtension.class)`）。
- 每个测试方法使用 `@DisplayName` 描述测试意图。
- 测试类命名：`{ClassName}Test.java`。
- 使用 jqwik 进行属性测试（Property-Based Testing）。
- 测试内部使用 inner class 作为 stub controller / mock 对象。

### 其他
- `GroupKeyResolver` 实现类必须注册为 Spring `@Component` Bean。
- `MsgId.java` 禁止手动编辑。修改 `.proto` 后运行 `./gradlew generateMsgId`。
- 模块消息号范围在 `msg-modules.properties` 中配置。
- Lombok：类级别用 `@Slf4j`、`@Getter`、`@Setter`；数据传输优先用 Java `record`。
