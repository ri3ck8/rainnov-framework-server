# 需求文档：游戏背包模块（Game Inventory）

## 简介

游戏背包模块为 Rainnov Framework Server 提供物品管理能力，允许玩家获取、使用、丢弃和整理背包中的物品。该模块遵循框架的注解驱动消息路由模式（`@MsgController` + `@MsgMapping`），通过 Protobuf 定义请求/响应消息，按用户维度串行处理背包操作，保证物品数据的一致性。

## 术语表

- **Inventory_Service**：背包服务，负责管理玩家背包数据的核心业务组件，注册为 Spring Bean
- **Inventory_Controller**：背包消息控制器，使用 `@MsgController` 注解标记，处理客户端背包相关请求
- **Item**：物品，背包中的最小存储单元，由物品 ID（itemId）和数量（count）描述
- **Item_Type**：物品类型，对物品的功能性分类（如 HEALING、BUFF、MATERIAL 等），同一 Item_Type 下可包含多个不同 itemId 的物品（如 HEALING 类型下有初级恢复药剂、中级恢复药剂、高级恢复药剂）
- **Item_Config**：物品配置，定义物品的静态属性（名称、Item_Type、最大堆叠数、是否可使用、是否可丢弃、效果参数 effectParams、过期策略 Expiration_Policy 等），其中 effectParams 为该物品使用时传递给 Item_Effect_Handler 的参数（如恢复量、持续时间、增益倍率等）
- **Item_Effect_Handler**：物品效果处理器，按 Item_Type 注册的效果执行组件，负责处理该类型物品使用时的具体效果逻辑，注册为 Spring Bean
- **Item_Effect_Context**：物品效果上下文，封装物品使用时的运行时信息（GameSession、itemId、Item_Config、使用数量），传递给 Item_Effect_Handler 执行效果
- **Expiration_Policy**：过期策略，定义物品是否具有时效性以及过期方式。包含两种模式：DURATION（相对过期）和 FIXED_DATE（绝对过期）。未配置过期策略的物品视为永不过期
- **Expiration_Mode**：过期模式枚举。DURATION 表示物品获得后经过指定天数到期，每个物品实例的到期时间取决于各自的获得时间；FIXED_DATE 表示物品在配置的固定日期到期，所有该物品实例统一到期
- **Acquired_Time**：获得时间，物品实例进入背包的时间戳，用于 DURATION 模式下计算到期时间
- **Slot**：格子，背包中的一个存储位置，由从 0 开始的索引标识，每个格子存放一种物品。当物品配置了 Expiration_Policy 时，Slot 同时记录该物品实例的 Acquired_Time
- **Stack**：堆叠，同一格子中相同物品的数量，受 Item_Config 中最大堆叠数限制
- **Capacity**：背包容量，玩家背包的最大格子数
- **GameSession**：游戏会话，框架提供的单连接状态封装，每用户独立消息队列保证串行处理

## 需求

### 需求 1：查询背包内容

**用户故事：** 作为玩家，我想查看背包中所有物品的信息，以便了解当前持有的物品和数量。

#### 验收标准

1. WHEN 客户端发送查询背包请求，THE Inventory_Controller SHALL 返回该玩家背包中所有非空 Slot 的物品信息（包含 slotIndex、itemId、count）
2. WHEN 客户端发送查询背包请求，THE Inventory_Controller SHALL 同时返回当前背包 Capacity
3. IF 玩家背包为空，THEN THE Inventory_Controller SHALL 返回空的物品列表和当前 Capacity
4. WHEN 客户端发送查询背包请求时，THE Inventory_Service SHALL 先执行过期物品检测，清理已过期的 Slot，再返回背包内容
5. WHEN Slot 中的物品配置了 Expiration_Policy 时，THE Inventory_Controller SHALL 在返回的物品信息中包含该 Slot 的到期时间（expireTime）

### 需求 2：添加物品到背包（服务端内部操作）

**用户故事：** 作为游戏系统，我想通过服务端内部逻辑向玩家背包中添加物品（如任务奖励、战斗掉落、GM 操作），以便玩家获得游戏内物品。

> 注：添加物品为服务端内部操作，不暴露为客户端消息接口（无 @MsgMapping）。其他服务端模块（任务系统、战斗掉落、GM 工具等）直接调用 `InventoryService.addItem()` 方法。

#### 验收标准

1. WHEN 服务端调用添加物品方法时，THE Inventory_Service SHALL 优先堆叠到已有相同 itemId 且未满 Stack 的 Slot 中
2. WHEN 已有 Slot 堆叠后仍有剩余数量，THE Inventory_Service SHALL 将剩余物品放入第一个空 Slot
3. IF 背包中没有足够空间容纳全部物品，THEN THE Inventory_Service SHALL 拒绝整个添加操作并返回背包已满错误码
4. WHEN 物品成功添加后，THE Inventory_Service SHALL 返回受影响的 Slot 列表
5. IF 请求中的 itemId 在 Item_Config 中不存在，THEN THE Inventory_Service SHALL 返回物品不存在错误码
6. IF 请求中的物品数量小于或等于 0，THEN THE Inventory_Service SHALL 返回参数非法错误码
7. WHEN 物品的 Item_Config 配置了 Expiration_Policy 且物品被放入新的空 Slot 时，THE Inventory_Service SHALL 记录当前时间作为该 Slot 的 Acquired_Time
8. WHEN 物品的 Expiration_Policy 为 FIXED_DATE 模式且配置的到期时间已过，THEN THE Inventory_Service SHALL 拒绝添加操作并返回物品已过期错误码

### 需求 3：使用物品

**用户故事：** 作为玩家，我想使用背包中的物品（如消耗品），以便根据物品类型获得对应的物品效果。

#### 验收标准

1. WHEN 客户端发送使用物品请求（指定 slotIndex 和使用数量），THE Inventory_Service SHALL 从指定 Slot 扣减相应数量
2. WHEN 扣减后 Slot 中物品数量为 0，THE Inventory_Service SHALL 清空该 Slot
3. IF 指定 Slot 为空或不存在，THEN THE Inventory_Service SHALL 返回格子为空错误码
4. IF 使用数量大于 Slot 中的物品数量，THEN THE Inventory_Service SHALL 返回数量不足错误码
5. IF 该物品在 Item_Config 中标记为不可使用，THEN THE Inventory_Service SHALL 返回物品不可使用错误码
6. WHEN 物品扣减成功后，THE Inventory_Service SHALL 根据该物品 Item_Config 中的 Item_Type 查找对应的 Item_Effect_Handler，并将 Item_Effect_Context 传递给该 Handler 执行效果
7. IF 该物品的 Item_Type 没有已注册的 Item_Effect_Handler，THEN THE Inventory_Service SHALL 返回效果处理器未注册错误码，且不扣减物品数量
8. WHEN 物品使用成功后，THE Inventory_Controller SHALL 返回更新后的 Slot 信息
9. IF 指定 Slot 中的物品已过期，THEN THE Inventory_Service SHALL 清空该 Slot 并返回物品已过期错误码，且不执行物品效果

### 需求 4：丢弃物品

**用户故事：** 作为玩家，我想丢弃背包中不需要的物品，以便腾出背包空间。

#### 验收标准

1. WHEN 客户端发送丢弃物品请求（指定 slotIndex 和丢弃数量），THE Inventory_Service SHALL 从指定 Slot 扣减相应数量
2. WHEN 扣减后 Slot 中物品数量为 0，THE Inventory_Service SHALL 清空该 Slot
3. IF 指定 Slot 为空或不存在，THEN THE Inventory_Service SHALL 返回格子为空错误码
4. IF 丢弃数量大于 Slot 中的物品数量，THEN THE Inventory_Service SHALL 返回数量不足错误码
5. IF 该物品在 Item_Config 中标记为不可丢弃，THEN THE Inventory_Service SHALL 返回物品不可丢弃错误码
6. WHEN 物品丢弃成功后，THE Inventory_Controller SHALL 返回更新后的 Slot 信息
7. IF 指定 Slot 中的物品已过期，THEN THE Inventory_Service SHALL 清空该 Slot 并返回物品已过期错误码

### 需求 5：整理背包

**用户故事：** 作为玩家，我想一键整理背包，以便将分散的同类物品合并堆叠并消除空格子。

#### 验收标准

1. WHEN 客户端发送整理背包请求，THE Inventory_Service SHALL 先执行过期物品检测并清理已过期的 Slot，再进行合并堆叠操作
2. WHEN 整理背包时，THE Inventory_Service SHALL 将所有相同 itemId 的物品合并堆叠（遵守最大 Stack 限制）
3. WHEN 合并堆叠完成后，THE Inventory_Service SHALL 将所有物品向前紧凑排列，消除中间的空 Slot
4. WHEN 整理完成后，THE Inventory_Controller SHALL 返回整理后的完整背包快照
5. IF 背包为空，THEN THE Inventory_Controller SHALL 返回空的物品列表

### 需求 6：交换格子位置

**用户故事：** 作为玩家，我想交换两个格子中的物品位置，以便自由排列背包中的物品。

#### 验收标准

1. WHEN 客户端发送交换格子请求（指定 sourceSlotIndex 和 targetSlotIndex），THE Inventory_Service SHALL 交换两个 Slot 的内容
2. IF sourceSlotIndex 或 targetSlotIndex 超出背包 Capacity 范围，THEN THE Inventory_Service SHALL 返回格子索引越界错误码
3. WHEN 其中一个 Slot 为空时，THE Inventory_Service SHALL 将非空 Slot 的物品移动到空 Slot 位置
4. WHEN 交换成功后，THE Inventory_Controller SHALL 返回两个受影响 Slot 的更新信息

### 需求 7：Protobuf 消息定义

**用户故事：** 作为开发者，我想为背包模块定义符合框架规范的 Protobuf 消息，以便客户端和服务端通过二进制协议通信。

#### 验收标准

1. THE Inventory_Controller SHALL 使用 `msg-modules.properties` 中分配的消息号范围定义背包模块消息
2. THE Proto 文件 SHALL 遵循 `C{msgId}_{MessageName}` 命名规范定义所有请求和响应消息
3. THE Proto 文件 SHALL 为每对请求/响应消息分配连续的消息号（响应消息号 = 请求消息号 + 1）
4. THE Proto 文件 SHALL 定义 `InventorySlot` 公共子消息，包含 slotIndex、itemId、count、expireTime 字段，供多个响应消息复用。其中 expireTime 为可选字段，仅当物品配置了 Expiration_Policy 时填充

### 需求 8：背包容量管理

**用户故事：** 作为游戏系统，我想为玩家设置和调整背包容量，以便通过游戏进度控制背包大小。

#### 验收标准

1. THE Inventory_Service SHALL 为每个玩家维护独立的背包 Capacity，默认值为可配置常量
2. WHEN 扩容请求到达时，THE Inventory_Service SHALL 将玩家背包 Capacity 增加指定数量
3. IF 扩容后 Capacity 超过系统最大上限，THEN THE Inventory_Service SHALL 返回容量已达上限错误码
4. IF 扩容数量小于或等于 0，THEN THE Inventory_Service SHALL 返回参数非法错误码

### 需求 9：物品变更通知

**用户故事：** 作为玩家，我想在背包物品发生变化时收到通知，以便客户端及时更新 UI 显示。

#### 验收标准

1. WHEN 背包物品因服务端逻辑变更（如系统发放、GM 操作）时，THE Inventory_Service SHALL 通过 `GameSession.send()` 主动推送物品变更通知
2. THE 变更通知消息 SHALL 包含所有受影响 Slot 的最新状态（slotIndex、itemId、count）
3. WHILE 玩家处于离线状态，THE Inventory_Service SHALL 将变更持久化，待玩家上线后通过查询背包接口获取最新数据

### 需求 10：物品效果处理器注册与扩展

**用户故事：** 作为开发者，我想通过注册不同 Item_Type 的效果处理器来定义物品使用效果，以便灵活扩展新的物品类型而无需修改核心背包逻辑。

#### 验收标准

1. THE Item_Effect_Handler SHALL 定义统一的效果执行接口，接收 Item_Effect_Context 作为参数
2. THE Item_Effect_Handler SHALL 声明自身处理的 Item_Type，用于注册时建立 Item_Type 到 Handler 的映射
3. WHEN 应用启动时，THE Inventory_Service SHALL 自动扫描所有注册为 Spring Bean 的 Item_Effect_Handler 实现，并按 Item_Type 建立映射
4. IF 同一 Item_Type 注册了多个 Item_Effect_Handler，THEN THE Inventory_Service SHALL 在启动时抛出异常并记录冲突的 Item_Type 和 Handler 信息
5. WHEN 新增物品类型时，开发者 SHALL 仅需实现 Item_Effect_Handler 接口并注册为 Spring Bean，无需修改 Inventory_Service 或 Inventory_Controller 代码
6. THE Item_Effect_Context SHALL 包含 GameSession、itemId、Item_Config 和使用数量，供 Item_Effect_Handler 读取物品的 effectParams 并执行对应效果逻辑

### 需求 11：物品过期检测与清理

**用户故事：** 作为游戏系统，我想自动检测和清理背包中已过期的物品，以便保证玩家无法使用过期物品，同时及时释放背包空间。

#### 验收标准

1. THE Inventory_Service SHALL 支持两种过期模式：DURATION 模式根据 Slot 的 Acquired_Time 加上 Item_Config 中配置的过期天数计算到期时间；FIXED_DATE 模式使用 Item_Config 中配置的固定到期日期作为到期时间
2. WHEN 物品的 Item_Config 未配置 Expiration_Policy 时，THE Inventory_Service SHALL 将该物品视为永不过期
3. WHEN Inventory_Service 执行过期检测时，THE Inventory_Service SHALL 遍历玩家背包中所有非空 Slot，将当前时间与每个 Slot 物品的到期时间进行比较
4. WHEN 检测到 Slot 中的物品已过期时，THE Inventory_Service SHALL 清空该 Slot（移除物品和 Acquired_Time）
5. WHEN 过期物品被清理后，THE Inventory_Service SHALL 通过 `GameSession.send()` 向玩家推送物品过期通知，包含被清理的 Slot 列表（slotIndex、itemId、count）
6. THE Inventory_Service SHALL 在以下时机触发过期检测：查询背包、使用物品、丢弃物品、整理背包操作执行前
7. IF DURATION 模式下 Item_Config 中配置的过期天数小于或等于 0，THEN THE Inventory_Service SHALL 在启动时记录配置警告日志并将该物品视为永不过期
