package com.rainnov.modules.inventory;

import com.rainnov.modules.inventory.model.SlotSnapshot;
import com.rainnov.framework.net.dispatch.MsgController;
import com.rainnov.framework.net.dispatch.MsgMapping;
import com.rainnov.framework.net.session.GameSession;
import com.rainnov.framework.proto.InventoryProto;
import com.rainnov.framework.proto.InventoryProto.*;
import com.rainnov.framework.proto.MsgId;

/**
 * 背包模块消息控制器。
 * 接收客户端背包相关请求，委托给 InventoryService 处理，构建 Protobuf 响应返回。
 */
@MsgController
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    // ─── Helper ─────────────────────────────────────────────────────────────────

    private InventoryProto.InventorySlot toProtoSlot(SlotSnapshot snap) {
        return InventoryProto.InventorySlot.newBuilder()
                .setSlotIndex(snap.slotIndex())
                .setItemId(snap.itemId())
                .setCount(snap.count())
                .setExpireTime(snap.expireTime())
                .build();
    }

    // ─── 9.2: 查询背包 ─────────────────────────────────────────────────────────

    @MsgMapping(MsgId.INVENTORY.QUERY_INVENTORY_REQ)
    public C5002_QueryInventoryResp queryInventory(GameSession session, C5001_QueryInventoryReq req) {
        InventoryService.QueryResult result = inventoryService.queryInventory(session);

        C5002_QueryInventoryResp.Builder builder = C5002_QueryInventoryResp.newBuilder()
                .setCapacity(result.capacity());
        for (SlotSnapshot snap : result.slots()) {
            builder.addSlots(toProtoSlot(snap));
        }
        return builder.build();
    }

    // ─── 9.3: 使用物品 ─────────────────────────────────────────────────────────

    @MsgMapping(MsgId.INVENTORY.USE_ITEM_REQ)
    public C5006_UseItemResp useItem(GameSession session, C5005_UseItemReq req) {
        InventoryService.UseResult result = inventoryService.useItem(session, req.getSlotIndex(), req.getCount());

        C5006_UseItemResp.Builder builder = C5006_UseItemResp.newBuilder();
        if (result.updatedSlot() != null) {
            builder.setUpdatedSlot(toProtoSlot(result.updatedSlot()));
        }
        return builder.build();
    }

    // ─── 9.4: 丢弃物品 ─────────────────────────────────────────────────────────

    @MsgMapping(MsgId.INVENTORY.DISCARD_ITEM_REQ)
    public C5008_DiscardItemResp discardItem(GameSession session, C5007_DiscardItemReq req) {
        InventoryService.DiscardResult result = inventoryService.discardItem(session, req.getSlotIndex(), req.getCount());

        C5008_DiscardItemResp.Builder builder = C5008_DiscardItemResp.newBuilder();
        if (result.updatedSlot() != null) {
            builder.setUpdatedSlot(toProtoSlot(result.updatedSlot()));
        }
        return builder.build();
    }

    // ─── 9.5: 整理背包 ─────────────────────────────────────────────────────────

    @MsgMapping(MsgId.INVENTORY.SORT_INVENTORY_REQ)
    public C5010_SortInventoryResp sortInventory(GameSession session, C5009_SortInventoryReq req) {
        InventoryService.SortResult result = inventoryService.sortInventory(session);

        C5010_SortInventoryResp.Builder builder = C5010_SortInventoryResp.newBuilder()
                .setCapacity(result.capacity());
        for (SlotSnapshot snap : result.slots()) {
            builder.addSlots(toProtoSlot(snap));
        }
        return builder.build();
    }

    // ─── 9.6: 交换格子 ─────────────────────────────────────────────────────────

    @MsgMapping(MsgId.INVENTORY.SWAP_SLOT_REQ)
    public C5012_SwapSlotResp swapSlot(GameSession session, C5011_SwapSlotReq req) {
        InventoryService.SwapResult result = inventoryService.swapSlots(session, req.getSourceSlotIndex(), req.getTargetSlotIndex());

        C5012_SwapSlotResp.Builder builder = C5012_SwapSlotResp.newBuilder();
        if (result.sourceSlot() != null) {
            builder.setSourceSlot(toProtoSlot(result.sourceSlot()));
        }
        if (result.targetSlot() != null) {
            builder.setTargetSlot(toProtoSlot(result.targetSlot()));
        }
        return builder.build();
    }

    // ─── 9.7: 扩容背包 ─────────────────────────────────────────────────────────

    @MsgMapping(MsgId.INVENTORY.EXPAND_CAPACITY_REQ)
    public C5014_ExpandCapacityResp expandCapacity(GameSession session, C5013_ExpandCapacityReq req) {
        InventoryService.ExpandResult result = inventoryService.expandCapacity(session, req.getAmount());

        return C5014_ExpandCapacityResp.newBuilder()
                .setNewCapacity(result.newCapacity())
                .build();
    }
}
