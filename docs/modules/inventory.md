# 背包模块（Inventory）

物品管理能力：查询、添加（服务端内部）、使用、丢弃、整理、交换格子、扩容、过期清理。所有操作走该用户 Session 的专属队列，按用户串行，保证单玩家数据一致。

消息号范围 5000 段（`msg-modules.properties` 中 `5000=INVENTORY`），实际使用 5001~5016。

## 分层

| 层级 | 组件 | 职责 |
|---|---|---|
| 控制层 | `InventoryController` | 消息路由、Proto 与 Model 转换 |
| 服务层 | `InventoryService` | 背包业务逻辑、内存数据、通知推送 |
| 模型层 | `PlayerInventory` / `Slot` / `ItemConfig` / `ExpirationPolicy` / `ItemType` / `ExpirationMode` / `SlotSnapshot` | 数据结构 |
| 配置层 | `ItemConfigRegistry` | 物品静态配置注册与查询 |
| 效果层 | `ItemEffectHandler` / `ItemEffectHandlerRegistry` / `ItemEffectContext` | 按 `ItemType` 分派的效果处理器 |

设计决策：

- `PlayerInventory` 为纯内存结构（`ConcurrentHashMap<Long, PlayerInventory>`），持久化留给上层
- 效果执行走策略模式，新增物品类型无需改动 Service / Controller
- 业务失败通过 Result record 的 `errorCode` 返回，不抛异常

## 消息一览

| msgId | 消息 | 说明 |
|---|---|---|
| 5001 / 5002 | `C5001_QueryInventoryReq` / `C5002_QueryInventoryResp` | 查询背包（返回非空 Slot + capacity + expireTime） |
| 5005 / 5006 | `C5005_UseItemReq` / `C5006_UseItemResp` | 使用物品 |
| 5007 / 5008 | `C5007_DiscardItemReq` / `C5008_DiscardItemResp` | 丢弃物品 |
| 5009 / 5010 | `C5009_SortInventoryReq` / `C5010_SortInventoryResp` | 整理背包 |
| 5011 / 5012 | `C5011_SwapSlotReq` / `C5012_SwapSlotResp` | 交换格子 |
| 5013 / 5014 | `C5013_ExpandCapacityReq` / `C5014_ExpandCapacityResp` | 扩容 |
| 5015 | `C5015_InventoryChangeNotify` | 服务端推送：物品变更 |
| 5016 | `C5016_ItemExpiredNotify` | 服务端推送：物品过期清理 |

公共子消息 `InventorySlot { slotIndex, itemId, count, expireTime }`，`expireTime` 仅在物品配置了过期策略时填充。

> 5003/5004（添加物品）未暴露为客户端接口 —— 添加物品是服务端内部操作，其他模块直接调用 `InventoryService.addItem(userId, itemId, count)` 或 `addItemAndNotify(session, itemId, count)`。

## Service 主要方法

```java
PlayerInventory getOrCreateInventory(long userId);
List<SlotSnapshot> cleanExpiredItems(GameSession session, PlayerInventory inventory);
QueryResult    queryInventory(GameSession session);
AddResult      addItem(long userId, int itemId, int count);          // 服务端内部
UseResult      useItem(GameSession session, int slotIndex, int count);
DiscardResult  discardItem(GameSession session, int slotIndex, int count);
SortResult     sortInventory(GameSession session);
SwapResult     swapSlots(GameSession session, int sourceIndex, int targetIndex);
ExpandResult   expandCapacity(GameSession session, int amount);
void           addItemAndNotify(GameSession session, int itemId, int count);  // 跨模块入口
```

各 `XxxResult` 为嵌套 record，首字段为 `errorCode`。

## 关键规则

添加物品：

- 先堆叠到已有同 itemId 且未满 `maxStack` 的 Slot，剩余量放入首个空 Slot
- 空间不足则**整单拒绝**（原子性），背包状态不变，返回 `INVENTORY_FULL`
- 物品配置了过期策略且进入新空 Slot 时，记录 `acquiredTime`
- `FIXED_DATE` 且已过期的物品拒绝添加，返回 `ITEM_EXPIRED`

使用物品：

- 校验顺序：Slot 非空 → 可使用 → 数量充足 → 未过期 → 效果处理器已注册
- 该 `ItemType` 无注册 Handler 时返回 `EFFECT_HANDLER_NOT_FOUND` 且**不扣减**
- 扣减成功后用 `ItemEffectContext`（session、itemId、ItemConfig、使用数量）调用对应 Handler

丢弃物品：校验可丢弃、数量充足、未过期；数量归零则清空 Slot。

整理背包：先清过期 → 合并同 itemId 堆叠（遵守 `maxStack`）→ 向前紧凑排列消除空隙 → 返回完整快照。

交换格子：索引越界返回 `SLOT_INDEX_OUT_OF_RANGE`；一侧为空则等价于移动；交换是自逆操作。

扩容：`amount <= 0` 返回 `INVALID_PARAM`，超过系统上限返回 `CAPACITY_LIMIT_REACHED`。

## 过期策略

| 模式 | 到期时间 |
|---|---|
| `DURATION` | `acquiredTime + durationDays * 86400000` |
| `FIXED_DATE` | `fixedExpireTime` |
| 未配置 | 永不过期 |

- 过期检测触发时机：查询背包、使用物品、丢弃物品、整理背包**之前**
- 检测到过期 → 清空 Slot（含 `acquiredTime`）→ 通过 `C5016_ItemExpiredNotify` 推送被清理的 Slot 列表
- `DURATION` 模式配置 `durationDays <= 0` 时，`ItemConfigRegistry` 启动记 WARN 并视为永不过期

## 错误码（`InventoryErrorCode`）

| 值 | 常量 | 含义 |
|---|---|---|
| 0 | SUCCESS | 成功 |
| 5001 | INVENTORY_FULL | 背包已满 |
| 5002 | ITEM_NOT_FOUND | 物品配置不存在 |
| 5003 | INVALID_PARAM | 参数非法 |
| 5004 | SLOT_EMPTY | 格子为空 |
| 5005 | INSUFFICIENT_COUNT | 数量不足 |
| 5006 | ITEM_NOT_USABLE | 物品不可使用 |
| 5007 | EFFECT_HANDLER_NOT_FOUND | 效果处理器未注册 |
| 5008 | ITEM_NOT_DISCARDABLE | 物品不可丢弃 |
| 5009 | SLOT_INDEX_OUT_OF_RANGE | 格子索引越界 |
| 5010 | CAPACITY_LIMIT_REACHED | 容量已达上限 |
| 5011 | ITEM_EXPIRED | 物品已过期 |

## 扩展新物品类型

1. 在 `ItemType` 增加枚举值
2. 实现 `ItemEffectHandler`（`getItemType()` + `handle(ItemEffectContext)`）并标注 `@Component`
3. 在 `ItemConfigRegistry` 注册物品配置（含 `effectParams`、过期策略）

无需修改 `InventoryService` / `InventoryController`。参考 `HealingEffectHandler`。

> 完整需求（EARS 格式）见 [../requirements/inventory.md](../requirements/inventory.md)，属性清单见 [../properties.md](../properties.md)。
