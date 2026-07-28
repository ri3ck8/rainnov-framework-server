# 需求：背包模块（EARS）

背包模块的验收标准，EARS 格式。编号保持稳定，[properties.md](../properties.md) 中的背包属性按本文编号引用。模块实现说明见 [modules/inventory.md](../modules/inventory.md)。

## 术语表

- **Inventory_Service**：背包核心业务组件（Spring Bean）
- **Inventory_Controller**：`@MsgController` 标注的背包消息控制器
- **Item**：物品，由 itemId + count 描述
- **Item_Type**：物品功能分类（HEALING / BUFF / MATERIAL 等），同一类型可含多个 itemId
- **Item_Config**：物品静态配置（名称、Item_Type、最大堆叠、可否使用/丢弃、effectParams、Expiration_Policy）
- **Item_Effect_Handler**：按 Item_Type 注册的效果执行组件（Spring Bean）
- **Item_Effect_Context**：效果上下文（GameSession、itemId、Item_Config、使用数量）
- **Expiration_Policy**：过期策略，含 DURATION（相对）与 FIXED_DATE（绝对）两种模式；未配置视为永不过期
- **Acquired_Time**：物品实例进入背包的时间戳，DURATION 模式下用于计算到期时间
- **Slot**：格子，索引从 0 开始，每格一种物品；物品有过期策略时同时记录 Acquired_Time
- **Stack**：同格子内相同物品的数量，受最大堆叠限制
- **Capacity**：背包最大格子数

## 需求 1：查询背包内容

1. WHEN 客户端发送查询背包请求，THE Inventory_Controller SHALL 返回所有非空 Slot 的物品信息（slotIndex、itemId、count）
2. WHEN 客户端发送查询背包请求，SHALL 同时返回当前 Capacity
3. IF 背包为空，THEN SHALL 返回空物品列表和当前 Capacity
4. WHEN 处理查询请求时，THE Inventory_Service SHALL 先执行过期检测并清理已过期 Slot，再返回内容
5. WHEN Slot 物品配置了 Expiration_Policy，SHALL 在返回信息中包含 expireTime

## 需求 2：添加物品到背包（服务端内部操作）

> 添加物品不暴露为客户端消息接口，其他服务端模块直接调用 `InventoryService.addItem()`。

1. WHEN 调用添加物品，THE Inventory_Service SHALL 优先堆叠到已有相同 itemId 且未满 Stack 的 Slot
2. WHEN 堆叠后仍有剩余，SHALL 将剩余物品放入第一个空 Slot
3. IF 空间不足以容纳全部物品，THEN SHALL 拒绝整个添加操作并返回背包已满错误码
4. WHEN 添加成功，SHALL 返回受影响的 Slot 列表
5. IF itemId 在 Item_Config 中不存在，THEN SHALL 返回物品不存在错误码
6. IF 数量 ≤ 0，THEN SHALL 返回参数非法错误码
7. WHEN 物品配置了 Expiration_Policy 且进入新的空 Slot，SHALL 记录当前时间为该 Slot 的 Acquired_Time
8. IF Expiration_Policy 为 FIXED_DATE 且已过期，THEN SHALL 拒绝添加并返回物品已过期错误码

## 需求 3：使用物品

1. WHEN 客户端发送使用物品请求（slotIndex、数量），THE Inventory_Service SHALL 从该 Slot 扣减相应数量
2. WHEN 扣减后数量为 0，SHALL 清空该 Slot
3. IF Slot 为空或不存在，THEN SHALL 返回格子为空错误码
4. IF 使用数量大于 Slot 内数量，THEN SHALL 返回数量不足错误码
5. IF 物品标记为不可使用，THEN SHALL 返回物品不可使用错误码
6. WHEN 扣减成功，SHALL 按 Item_Type 查找 Item_Effect_Handler 并传入 Item_Effect_Context 执行效果
7. IF 该 Item_Type 无已注册 Handler，THEN SHALL 返回效果处理器未注册错误码，且不扣减数量
8. WHEN 使用成功，THE Inventory_Controller SHALL 返回更新后的 Slot 信息
9. IF 该 Slot 物品已过期，THEN SHALL 清空该 Slot、返回物品已过期错误码，且不执行效果

## 需求 4：丢弃物品

1. WHEN 客户端发送丢弃请求（slotIndex、数量），SHALL 从该 Slot 扣减相应数量
2. WHEN 扣减后数量为 0，SHALL 清空该 Slot
3. IF Slot 为空或不存在，THEN SHALL 返回格子为空错误码
4. IF 丢弃数量大于 Slot 内数量，THEN SHALL 返回数量不足错误码
5. IF 物品标记为不可丢弃，THEN SHALL 返回物品不可丢弃错误码
6. WHEN 丢弃成功，SHALL 返回更新后的 Slot 信息
7. IF 该 Slot 物品已过期，THEN SHALL 清空该 Slot 并返回物品已过期错误码

## 需求 5：整理背包

1. WHEN 客户端发送整理请求，SHALL 先执行过期检测并清理，再合并堆叠
2. WHEN 整理时，SHALL 合并所有相同 itemId 的物品（遵守最大 Stack）
3. WHEN 合并完成，SHALL 将物品向前紧凑排列，消除中间空 Slot
4. WHEN 整理完成，SHALL 返回整理后的完整背包快照
5. IF 背包为空，THEN SHALL 返回空物品列表

## 需求 6：交换格子位置

1. WHEN 客户端发送交换请求（sourceSlotIndex、targetSlotIndex），SHALL 交换两个 Slot 的内容
2. IF 任一索引超出 Capacity 范围，THEN SHALL 返回格子索引越界错误码
3. WHEN 其中一个 Slot 为空，SHALL 将非空 Slot 的物品移动到空 Slot
4. WHEN 交换成功，SHALL 返回两个受影响 Slot 的更新信息

## 需求 7：Protobuf 消息定义

1. THE Inventory_Controller SHALL 使用 `msg-modules.properties` 中分配的消息号范围
2. THE Proto 文件 SHALL 遵循 `C{msgId}_{MessageName}` 命名规范
3. THE Proto 文件 SHALL 为每对请求/响应分配连续消息号（响应 = 请求 + 1）
4. THE Proto 文件 SHALL 定义公共子消息 `InventorySlot`（slotIndex、itemId、count、expireTime），expireTime 仅在配置过期策略时填充

## 需求 8：背包容量管理

1. THE Inventory_Service SHALL 为每个玩家维护独立 Capacity，默认值为可配置常量
2. WHEN 扩容请求到达，SHALL 将 Capacity 增加指定数量
3. IF 扩容后超过系统上限，THEN SHALL 返回容量已达上限错误码
4. IF 扩容数量 ≤ 0，THEN SHALL 返回参数非法错误码

## 需求 9：物品变更通知

1. WHEN 背包因服务端逻辑变更（系统发放、GM 操作），SHALL 通过 `GameSession.send()` 主动推送变更通知
2. THE 变更通知 SHALL 包含所有受影响 Slot 的最新状态（slotIndex、itemId、count）
3. WHILE 玩家离线，SHALL 将变更持久化，待上线后通过查询背包获取最新数据

## 需求 10：物品效果处理器注册与扩展

1. THE Item_Effect_Handler SHALL 定义统一效果执行接口，接收 Item_Effect_Context
2. THE Item_Effect_Handler SHALL 声明自身处理的 Item_Type
3. WHEN 应用启动，SHALL 自动扫描所有 Spring Bean 形态的 Handler 并按 Item_Type 建表
4. IF 同一 Item_Type 注册多个 Handler，THEN SHALL 启动时抛异常并记录冲突信息
5. WHEN 新增物品类型，开发者 SHALL 仅需实现接口并注册为 Bean，无需改动 Service / Controller
6. THE Item_Effect_Context SHALL 包含 GameSession、itemId、Item_Config 与使用数量

## 需求 11：物品过期检测与清理

1. THE Inventory_Service SHALL 支持两种过期模式：DURATION（Acquired_Time + 配置天数）与 FIXED_DATE（配置固定日期）
2. WHEN 物品未配置 Expiration_Policy，SHALL 视为永不过期
3. WHEN 执行过期检测，SHALL 遍历所有非空 Slot 比较当前时间与到期时间
4. WHEN 检测到过期，SHALL 清空该 Slot（含 Acquired_Time）
5. WHEN 过期物品被清理，SHALL 通过 `GameSession.send()` 推送过期通知，包含被清理的 Slot 列表
6. THE Inventory_Service SHALL 在查询背包、使用物品、丢弃物品、整理背包**之前**触发过期检测
7. IF DURATION 模式配置的天数 ≤ 0，THEN SHALL 启动时记 WARN 并视为永不过期
