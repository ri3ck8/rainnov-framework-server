# 正确性属性（Correctness Properties）

属性是系统所有合法执行中都应成立的行为陈述，是人类可读规范与可验证测试之间的桥梁。属性测试用 jqwik 实现，约定见 [testing.md](testing.md)。

每条属性标注它验证的需求编号：框架属性对应 [requirements/framework.md](requirements/framework.md)，背包属性对应 [requirements/inventory.md](requirements/inventory.md)。

## 框架属性

编号保留历史序号。属性 14~17 曾覆盖已移除的分组队列与 Redis 分布式队列，现作废。

| # | 属性 | 验证需求 |
|---|---|---|
| 1 | 未认证访问 requireAuth=true 的接口，返回 `error_code=401` 且不断开连接 | 3.2, 3.3 |
| 2 | requireAuth=false 的接口允许未认证访问，消息正常进入队列 | 3.2, 13.4 |
| 3 | 通过校验的消息一律进入该 Session 的专属队列，同一用户严格按入队顺序执行 | 3.4 |
| 4 | 停机触发后到达的消息不再入队 | 1.4, 3.7 |
| 5 | 合法的 `@MsgMapping` 方法注册后 `find(msgId)` 返回含 bean、method、payloadType、requireAuth 的 MethodInvoker；未注册的 msgId 返回 null | 4.2, 4.6 |
| 6 | msgId 重复、参数非 Protobuf Message、注解 value 与类名前缀不一致时启动抛 `IllegalStateException` | 4.3, 4.4, 4.5, 11.6 |
| 7 | 任意连接/断开序列后，在线连接数等于活跃 Session 实际数量；按 Channel / userId 查找与创建时一致；断开后返回 null | 5.1~5.5, 10.3 |
| 8 | `bindUser(userId)` 后 `isAuthenticated()` 为 true 且 `getUserId()` 返回该 userId | 5.6 |
| 9 | 已注册 msgId 的合法 payload 被反序列化为正确类型并反射调用对应方法 | 6.4, 13.3 |
| 10 | 自动包装的响应 msgId = 请求 msgId + 1，seq = 请求 seq | 6.5 |
| 11 | 单条消息抛异常后消费线程继续处理后续消息 | 6.7 |
| 12 | 未注册 msgId 返回 `error_code=404` | 12.1 |
| 13 | payload 无法反序列化返回 `error_code=400`；Handler 抛异常返回 `error_code=500` | 12.2, 12.3 |
| 14~17 | *（已作废：原分组队列 / 分布式队列相关属性）* | — |
| 18 | 超过令牌桶速率的消息被静默丢弃，且 messagesDropped 相应递增 | 9.2, 9.3, 10.4 |
| 19 | 任意连接/消息事件序列后，ServerMetrics 四个计数器准确反映事件次数 | 10.1, 10.3 |
| 20 | generateMsgId 生成的 MsgId.java 包含全部消息号常量，并按配置范围归入模块类；范围外归入 `MODULE_{base}` | 11.1~11.4 |
| 21 | 在线连接数达上限时新连接被立即关闭且不发送响应，在线数不超过上限 | 2.2 |

## 背包属性

| # | 属性 | 验证需求 |
|---|---|---|
| 1 | 查询返回的 Slot 列表与内存中所有非空 Slot 一一对应，capacity 一致，expireTime 按模式正确计算 | 1.1, 1.2, 1.5 |
| 2 | 有效添加后该 itemId 总数 = 操作前总数 + 添加数量，且每个 Slot 不超过 maxStack | 2.1, 2.2, 2.4 |
| 3 | 空间不足时整单拒绝，背包状态（itemId、count、acquiredTime）与操作前完全一致 | 2.3 |
| 4 | 配置了过期策略的物品进入新空 Slot 时，acquiredTime 被记录为当前时间（非零） | 2.7 |
| 5 | 有效使用后该 Slot 数量 = 操作前 − 使用数量；归零则 Slot 为空 | 3.1, 3.2, 3.8 |
| 6 | 该 ItemType 无注册 Handler 时操作被拒绝，Slot 数量不变 | 3.7 |
| 7 | 有效丢弃后该 Slot 数量 = 操作前 − 丢弃数量；归零则 Slot 为空 | 4.1, 4.2, 4.6 |
| 8 | 整理后每种 itemId 总数守恒、每个 Slot 不超过 maxStack、非空 Slot 连续排在前面 | 5.2, 5.3 |
| 9 | 对两个有效索引执行交换两次后，背包状态与操作前完全一致（自逆） | 6.1 |
| 10 | 有效扩容后 capacity = 操作前 + amount，原有 Slot 数据不受影响 | 8.2 |
| 11 | 过期检测后：已过期 Slot 全部清空，未过期与无策略物品保持不变；判定规则 DURATION 为 `now >= acquiredTime + durationDays * 86400000`，FIXED_DATE 为 `now >= fixedExpireTime` | 11.1~11.4, 1.4 |
| 12 | 若两个及以上 Handler 声明同一 ItemType，注册过程抛异常 | 10.4 |
