# 技术栈与构建

## 语言与运行时

- Java 25（大量使用虚拟线程），Gradle toolchain 锁定 25
- Gradle 9.5.1（项目自带 Wrapper：`gradlew` / `gradlew.bat`）

版本口径与 `rainnov-lockstep-server` 保持一致（Java 25 / Spring Boot 4.1.0 / Protobuf 4.31.1 / Gradle 9.5.1），升级时两个项目同步调整。

## 框架与依赖库

| 依赖 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 4.1.0 | 应用框架、依赖注入、调度、生命周期管理 |
| Netty | 4.1.115.Final | WebSocket 服务器、异步 I/O、Channel Pipeline |
| Protobuf | 4.31.1 | 二进制消息序列化（proto3） |
| Guava | 33.3.1-jre | `RateLimiter` 令牌桶限流 |
| Lombok | Boot 管理 | 减少样板代码（`@Slf4j`、`@Getter`、`@Setter`） |
| JUnit 5 + Mockito | Boot 管理 | 单元测试（`spring-boot-starter-test`） |
| jqwik | 1.9.2 | 属性测试（Property-Based Testing） |

Gradle 插件：`org.springframework.boot 4.1.0`、`io.spring.dependency-management 1.1.7`、`com.google.protobuf 0.9.5`。

分组队列移除后，原先为其服务的 `spring-boot-starter-data-redis` 与 `jackson-databind` 已从 `build.gradle` 删除，基础依赖改为 `spring-boot-starter`。业务侧若要用分布式锁，按需自行引入 Redis / Redisson 等依赖。

## Protobuf 与代码生成

- Proto 源文件目录：`src/main/proto/`
- 模块映射配置：`src/main/proto/msg-modules.properties`（格式：`{起始msgId}={MODULE_NAME}`）
- 自定义 Gradle 任务 `generateMsgId` 扫描 `.proto` 中 `C{msgId}_{Name}` 消息，生成 `MsgId.java`
- 构建链：`generateProto` → `generateMsgId` → `compileJava`
- `MsgId.java` 为自动生成文件 — **禁止手动编辑**

生成算法细节见 [protocol.md](protocol.md)。

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

# 清理构建产物
./gradlew clean
```

测试客户端 `com.rainnov.client.GameClient` 目前没有配置 Gradle `application` 插件，直接从 IDE 运行其 `main` 方法即可（需要 `run` 任务时须先在 `build.gradle` 中添加 `application` 插件或注册 `JavaExec` 任务）。

## 配置项

`src/main/resources/application.yml` 当前只设置了 `spring.application.name`，以下配置项均有代码默认值，按需覆盖：

| 配置项 | 默认值 | 位置 |
|---|---|---|
| `game.server.port` | 8888 | `NettyServer` |
| `game.server.max-connections` | 10000 | `GameChannelInitializer` |
| `game.server.rate-limit.per-second` | 30 | `SessionManager`（注入每个 `GameSession` 的令牌桶速率） |

## 编码

所有 Java 编译与 `JavaExec` 执行均使用 UTF-8（`build.gradle` 中通过 `options.encoding` 与 `-Dfile.encoding` 配置）。
