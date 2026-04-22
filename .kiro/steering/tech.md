# 技术栈与构建

## 语言与运行时

- Java 21（大量使用虚拟线程）
- Gradle 9.x（项目自带 Wrapper：`gradlew` / `gradlew.bat`）

## 框架与依赖库

| 依赖 | 用途 |
|---|---|
| Spring Boot 4.0.5 | 应用框架、依赖注入、调度、生命周期管理 |
| Netty 4.1.115 | WebSocket 服务器、异步 I/O、Channel Pipeline |
| Protobuf 4.29.3 | 二进制消息序列化（proto3） |
| Spring Data Redis | 分布式队列后端（可选，按条件加载） |
| Guava 33.3.1 | `RateLimiter` 令牌桶限流 |
| Jackson | `DistributedQueueManager` 的 JSON 序列化 |
| Lombok | 减少样板代码（`@Slf4j`、`@Getter`、`@Setter`） |
| JUnit 5 + Mockito | 单元测试（通过 `spring-boot-starter-test`） |
| jqwik 1.9.2 | 属性测试（Property-Based Testing） |

## Protobuf 与代码生成

- Proto 源文件目录：`src/main/proto/`
- 模块映射配置：`src/main/proto/msg-modules.properties`（格式：`{起始msgId}={MODULE_NAME}`）
- 自定义 Gradle 任务 `generateMsgId` 扫描 `.proto` 中 `C{msgId}_{Name}` 消息，生成 `MsgId.java`
- 构建链：`generateProto` → `generateMsgId` → `compileJava`
- `MsgId.java` 为自动生成文件 — **禁止手动编辑**

## 常用命令

```bash
# 完整构建（Proto 生成 + 编译 + 测试）
./gradlew build

# 仅运行测试
./gradlew test

# 启动服务器（默认 ws://localhost:8888/ws）
./gradlew bootRun

# 从 proto 文件重新生成 MsgId.java
./gradlew generateMsgId

# 运行测试客户端
./gradlew run -PmainClass=com.rainnov.client.GameClient

# 清理构建产物
./gradlew clean
```

## 配置项

`application.properties` 中的关键配置：

```properties
game.server.port=8888
game.server.max-connections=10000
game.server.rate-limit.per-second=30
```

## 编码

所有 Java 编译和执行均使用 UTF-8 编码（在 `build.gradle` 中配置）。
