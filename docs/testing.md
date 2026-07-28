# 测试约定

```bash
./gradlew test        # JUnit Platform（JUnit 5 + jqwik）
```

## 目录与命名

- 测试目录镜像 main 包结构：`src/test/java/com/rainnov/...`
- 测试类命名 `{ClassName}Test.java`，属性测试可命名 `{ClassName}PropertyTest.java`
- 每个测试方法加 `@DisplayName` 描述测试意图
- 测试内部用 inner class 作为 stub controller / mock 对象

## 分层

单元测试（JUnit 5 + Mockito，`@ExtendWith(MockitoExtension.class)`）：

- `MsgControllerRegistry`：注解扫描、方法签名校验、重复 msgId 检测、MethodInvoker 构建
- `MessageDispatcher`：mock Session/Registry，验证路由、未认证拦截、异常处理
- `SessionManager` / `GameSession`：创建/销毁/查找、队列与消费线程行为
- 业务 Service：错误码分支、边界条件

集成测试：

- 启动嵌入式 Netty Server，用 WebSocket 客户端发真实 Protobuf 消息，验证端到端流程
- 验证心跳超时后连接被正确关闭
- `@MsgController` + `@MsgMapping` 注册与路由正确性

属性测试（jqwik 1.9.2）：

- 任意合法 msgId 的消息必定得到响应（成功或错误码）
- 未注册 msgId 必定返回 404
- 并发 N 个连接收发时 `onlineCount()` 始终准确
- 业务侧不变量，如背包物品数量守恒、整理后紧凑排列、交换操作自逆

## 正确性属性来源

框架与背包模块的属性清单见 [properties.md](properties.md)，对应的 EARS 验收标准见 [requirements/framework.md](requirements/framework.md) 与 [requirements/inventory.md](requirements/inventory.md)。

新增属性测试时保持“属性 → 需求编号 → 测试类”的三段对应关系。

## 测试缺口（背包模块）

框架层测试已覆盖，背包模块目前只有主干代码、没有测试。待补清单（括号内为对应属性 / 需求）：

| 待补测试 | 覆盖 |
|---|---|
| `model/PlayerInventoryTest` | 空背包查询、单 Slot 操作、容量边界、堆叠与首个空格查找（需求 1.1~1.3, 2.1, 2.2） |
| `InventoryServiceTest` | 错误码分支：itemId 不存在、数量 ≤ 0、空 Slot、不可使用/不可丢弃、索引越界、扩容超上限、物品过期（需求 2.3, 2.5, 2.6, 3.3~3.9, 4.3~4.7, 6.2, 8.3, 8.4） |
| `config/ItemConfigRegistryTest` | 配置注册与查找、`durationDays <= 0` 警告（需求 11.7） |
| `InventoryControllerIntegrationTest` | `@MsgController` + `@MsgMapping` 注册与路由（需求 7.1~7.3） |
| `effect/ItemEffectHandlerRegistryPropertyTest` | 背包属性 12 |
| `InventoryQueryPropertyTest` / `InventoryAddPropertyTest` / `InventoryUsePropertyTest` / `InventoryDiscardPropertyTest` | 背包属性 1~7 |
| `InventorySortPropertyTest` / `InventorySwapPropertyTest` / `InventoryExpandPropertyTest` | 背包属性 8~10 |
| `ExpirationPropertyTest` | 背包属性 11 |
