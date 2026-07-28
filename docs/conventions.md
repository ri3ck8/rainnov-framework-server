# 编码与命名约定

## Proto 消息

- 消息名 `C{msgId}_{MessageName}`；请求奇数，响应 = 请求 + 1；推送同样带 `C{msgId}_` 前缀
- `java_outer_classname` 按模块命名（`InventoryProto`、`LoginProto`）
- 完整规范见 [protocol.md](protocol.md)

## Controller 层

```java
@MsgController
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {   // 构造器注入
        this.service = service;
    }

    @MsgMapping(MsgId.INVENTORY.QUERY_INVENTORY_REQ)
    public C5002_QueryInventoryResp query(GameSession session, C5001_QueryInventoryReq req) {
        InventoryService.QueryResult result = service.query(session);
        return C5002_QueryInventoryResp.newBuilder()
                .setCapacity(result.capacity())
                .addAllSlots(result.slots().stream().map(this::toProtoSlot).toList())
                .build();
    }

    private InventorySlot toProtoSlot(SlotSnapshot snapshot) { ... }
}
```

- 方法签名固定 `(GameSession session, XxxReq req)`
- 返回 Protobuf `Message` → 框架自动包装为 `GameMessage`（msgId = 请求 + 1，seq 回填）并发送
- 返回 `void` → 业务自行调用 `session.send()`
- `@MsgMapping(requireAuth = false)` 用于免登录接口（如登录），默认 `true`
- 消息统一按用户维度串行；跨用户共享状态（队伍、公会、房间）在 Service 内用锁或分布式锁显式控制
- Controller 只做请求解析与响应构建，业务逻辑全部委托 Service
- 使用框架的 `@MsgController`，不是 Spring 的 `@Controller`
- 多处复用的 Model → Proto 转换提取为 private helper

## 模块结构

每个业务模块放在 `com.rainnov.modules.{moduleName}/` 下：

| 文件 | 约定 |
|---|---|
| `{Module}Controller.java` | `@MsgController`，构造器注入 Service |
| `{Module}Service.java` | `@Component` + `@Slf4j`，核心业务逻辑 |
| `{Module}ErrorCode.java` | `public static final int` 常量，`SUCCESS = 0`，私有构造器 |
| `model/` | 数据模型、枚举、record |
| `config/` | 配置注册中心（`@Component`） |
| `{effect}/` | 策略处理器（可选） |

## Service 层

- 注册为 `@Component`，构造器注入依赖
- 内存存储用 `ConcurrentHashMap<Long, PlayerXxx>`，配 `getOrCreate(userId)`
- 方法返回值使用嵌套在 Service 内的 Java `record` 作为 Result 类型（`QueryResult`、`AddResult`…），携带 `errorCode` + 业务数据，不靠抛异常传递业务失败
- 参数校验前置，快速失败
- 可能失败的外部调用（如效果执行）用 try-catch 包裹并记日志
- 服务端主动推送：`session.send(GameMessage.newBuilder().setMsgId(MsgId.{MODULE}.{NOTIFY}).setPayload(...).build())`
- 跨模块调用通过构造器注入对方 Service，并暴露语义化 public 方法（如 `addItemAndNotify`）

## 数据模型

- 配置对象用 `record`（不可变），运行时状态用 class（可变，Lombok `@Getter`/`@Setter`）
- 返回给 Controller 的快照用 `record`（如 `SlotSnapshot`）
- nullable 字段在 javadoc 注明
- 枚举放 `model/` 包

## 策略处理器（Handler + Registry）

- 接口声明 `getType()` + `handle(context)`
- Registry 实现 `InitializingBean`，构造器注入 `List<Handler>`，用 `EnumMap` 建表；同类型重复注册抛 `IllegalStateException`
- 每个 Handler 是 `@Component`，Spring 自动收集
- 参考 `ItemEffectHandler` / `ItemEffectHandlerRegistry`，模板见 [guides/handler-strategy.md](guides/handler-strategy.md)

## 其他

- 分布式锁工具（若业务需要）由对应模块自行提供并注册为 Spring `@Component`，锁 key 用 `{实体}:{id}` 形式并设置过期时间
- `MsgId.java` 禁止手动编辑，改 `.proto` 后执行 `./gradlew generateMsgId`
- 模块消息号范围在 `src/main/proto/msg-modules.properties` 配置
- Lombok：类级别 `@Slf4j`、`@Getter`、`@Setter`；数据传输优先 `record`
- 所有 Spring Bean 使用构造器注入，不用字段 `@Autowired`
- Java 编译与运行统一 UTF-8
