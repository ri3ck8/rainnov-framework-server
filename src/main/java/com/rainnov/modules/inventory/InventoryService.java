package com.rainnov.modules.inventory;

import com.rainnov.modules.inventory.config.ItemConfigRegistry;
import com.rainnov.modules.inventory.effect.ItemEffectContext;
import com.rainnov.modules.inventory.effect.ItemEffectHandler;
import com.rainnov.modules.inventory.effect.ItemEffectHandlerRegistry;
import com.rainnov.framework.net.session.GameSession;
import com.rainnov.framework.proto.GameMessageProto.GameMessage;
import com.rainnov.framework.proto.InventoryProto;
import com.rainnov.framework.proto.MsgId;
import com.rainnov.modules.inventory.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InventoryService {

    public static final int DEFAULT_CAPACITY = 30;
    public static final int MAX_CAPACITY = 200;

    private final ItemConfigRegistry itemConfigRegistry;
    private final ItemEffectHandlerRegistry effectHandlerRegistry;
    private final ConcurrentHashMap<Long, PlayerInventory> inventories = new ConcurrentHashMap<>();

    public InventoryService(ItemConfigRegistry itemConfigRegistry, ItemEffectHandlerRegistry effectHandlerRegistry) {
        this.itemConfigRegistry = itemConfigRegistry;
        this.effectHandlerRegistry = effectHandlerRegistry;
    }

    // ─── 6.1: getOrCreateInventory ──────────────────────────────────────────────

    public PlayerInventory getOrCreateInventory(long userId) {
        return inventories.computeIfAbsent(userId, id -> new PlayerInventory(id, DEFAULT_CAPACITY));
    }

    // ─── 6.2: cleanExpiredItems ─────────────────────────────────────────────────

    public List<SlotSnapshot> cleanExpiredItems(GameSession session, PlayerInventory inventory) {
        List<SlotSnapshot> expiredSnapshots = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (int i = 0; i < inventory.getCapacity(); i++) {
            Slot slot = inventory.getSlot(i);
            if (slot.isEmpty()) {
                continue;
            }

            ItemConfig config = itemConfigRegistry.getConfig(slot.getItemId());
            if (config == null) {
                continue;
            }

            ExpirationPolicy policy = config.expirationPolicy();
            if (policy == null) {
                continue;
            }

            if (policy.mode() == ExpirationMode.DURATION && policy.durationDays() <= 0) {
                continue; // treated as never expires per requirement 11.7
            }

            boolean expired = false;
            if (policy.mode() == ExpirationMode.DURATION) {
                expired = now >= slot.getAcquiredTime() + policy.durationDays() * 86400000L;
            } else if (policy.mode() == ExpirationMode.FIXED_DATE) {
                expired = now >= policy.fixedExpireTime();
            }

            if (expired) {
                expiredSnapshots.add(new SlotSnapshot(i, slot.getItemId(), slot.getCount(), 0));
                slot.clear();
            }
        }

        if (!expiredSnapshots.isEmpty() && session != null) {
            InventoryProto.C5016_ItemExpiredNotify.Builder notifyBuilder =
                    InventoryProto.C5016_ItemExpiredNotify.newBuilder();
            for (SlotSnapshot snap : expiredSnapshots) {
                notifyBuilder.addExpiredSlots(InventoryProto.InventorySlot.newBuilder()
                        .setSlotIndex(snap.slotIndex())
                        .setItemId(snap.itemId())
                        .setCount(snap.count())
                        .build());
            }
            session.send(GameMessage.newBuilder()
                    .setMsgId(MsgId.INVENTORY.ITEM_EXPIRED_NOTIFY)
                    .setPayload(notifyBuilder.build().toByteString())
                    .build());
        }

        return expiredSnapshots;
    }

    // ─── 6.4: queryInventory ────────────────────────────────────────────────────

    public QueryResult queryInventory(GameSession session) {
        long userId = session.getUserId();
        PlayerInventory inventory = getOrCreateInventory(userId);
        cleanExpiredItems(session, inventory);

        List<SlotSnapshot> slots = new ArrayList<>();
        for (int i = 0; i < inventory.getCapacity(); i++) {
            Slot slot = inventory.getSlot(i);
            if (slot.isEmpty()) {
                continue;
            }
            long expireTime = calculateExpireTime(slot);
            slots.add(new SlotSnapshot(i, slot.getItemId(), slot.getCount(), expireTime));
        }

        return new QueryResult(InventoryErrorCode.SUCCESS, slots, inventory.getCapacity());
    }

    private long calculateExpireTime(Slot slot) {
        ItemConfig config = itemConfigRegistry.getConfig(slot.getItemId());
        if (config == null) {
            return 0;
        }
        ExpirationPolicy policy = config.expirationPolicy();
        if (policy == null) {
            return 0;
        }
        if (policy.mode() == ExpirationMode.DURATION) {
            if (policy.durationDays() <= 0) {
                return 0; // never expires
            }
            return slot.getAcquiredTime() + policy.durationDays() * 86400000L;
        } else if (policy.mode() == ExpirationMode.FIXED_DATE) {
            return policy.fixedExpireTime();
        }
        return 0;
    }

    // ─── 6.6: addItem ───────────────────────────────────────────────────────────

    public AddResult addItem(long userId, int itemId, int count) {
        ItemConfig config = itemConfigRegistry.getConfig(itemId);
        if (config == null) {
            return new AddResult(InventoryErrorCode.ITEM_NOT_FOUND, List.of());
        }
        if (count <= 0) {
            return new AddResult(InventoryErrorCode.INVALID_PARAM, List.of());
        }

        // Check FIXED_DATE already expired
        ExpirationPolicy policy = config.expirationPolicy();
        if (policy != null && policy.mode() == ExpirationMode.FIXED_DATE
                && policy.fixedExpireTime() <= System.currentTimeMillis()) {
            return new AddResult(InventoryErrorCode.ITEM_EXPIRED, List.of());
        }

        PlayerInventory inventory = getOrCreateInventory(userId);
        int maxStack = config.maxStack();

        // Calculate available space
        List<Integer> stackableSlots = inventory.findStackableSlots(itemId, maxStack);
        int availableInStacks = 0;
        for (int idx : stackableSlots) {
            availableInStacks += maxStack - inventory.getSlot(idx).getCount();
        }
        int emptySlotCount = inventory.countEmptySlots();
        long totalAvailable = availableInStacks + (long) emptySlotCount * maxStack;

        if (totalAvailable < count) {
            return new AddResult(InventoryErrorCode.INVENTORY_FULL, List.of());
        }

        // Execute: fill stackable slots first, then empty slots
        int remaining = count;
        List<SlotSnapshot> affectedSlots = new ArrayList<>();
        boolean hasExpirationPolicy = policy != null;

        // Fill existing stackable slots
        for (int idx : stackableSlots) {
            if (remaining <= 0) break;
            Slot slot = inventory.getSlot(idx);
            int canAdd = maxStack - slot.getCount();
            int toAdd = Math.min(canAdd, remaining);
            slot.addCount(toAdd);
            remaining -= toAdd;
            affectedSlots.add(new SlotSnapshot(idx, slot.getItemId(), slot.getCount(), calculateExpireTime(slot)));
        }

        // Fill empty slots
        while (remaining > 0) {
            int emptyIdx = inventory.findFirstEmptySlot();
            if (emptyIdx == -1) break; // should not happen since we checked space
            Slot slot = inventory.getSlot(emptyIdx);
            int toAdd = Math.min(maxStack, remaining);
            slot.setItemId(itemId);
            slot.setCount(toAdd);
            if (hasExpirationPolicy) {
                slot.setAcquiredTime(System.currentTimeMillis());
            }
            remaining -= toAdd;
            affectedSlots.add(new SlotSnapshot(emptyIdx, slot.getItemId(), slot.getCount(), calculateExpireTime(slot)));
        }

        return new AddResult(InventoryErrorCode.SUCCESS, affectedSlots);
    }

    // ─── 6.8: useItem ───────────────────────────────────────────────────────────

    public UseResult useItem(GameSession session, int slotIndex, int count) {
        long userId = session.getUserId();
        PlayerInventory inventory = getOrCreateInventory(userId);
        cleanExpiredItems(session, inventory);

        // Validate slotIndex
        if (slotIndex < 0 || slotIndex >= inventory.getCapacity()) {
            return new UseResult(InventoryErrorCode.SLOT_INDEX_OUT_OF_RANGE, null);
        }

        Slot slot = inventory.getSlot(slotIndex);

        // Validate slot not empty
        if (slot.isEmpty()) {
            return new UseResult(InventoryErrorCode.SLOT_EMPTY, null);
        }

        // Check if item is expired (single slot check after cleanExpiredItems)
        ItemConfig config = itemConfigRegistry.getConfig(slot.getItemId());
        if (config != null && isSlotExpired(slot, config)) {
            slot.clear();
            return new UseResult(InventoryErrorCode.ITEM_EXPIRED, new SlotSnapshot(slotIndex, 0, 0, 0));
        }

        // Validate usable
        if (config == null || !config.usable()) {
            return new UseResult(InventoryErrorCode.ITEM_NOT_USABLE, null);
        }

        // Validate count
        if (count > slot.getCount()) {
            return new UseResult(InventoryErrorCode.INSUFFICIENT_COUNT, null);
        }

        // Get effect handler - don't deduct if no handler
        ItemEffectHandler handler = effectHandlerRegistry.getHandler(config.itemType());
        if (handler == null) {
            return new UseResult(InventoryErrorCode.EFFECT_HANDLER_NOT_FOUND, null);
        }

        // Deduct count
        int itemId = slot.getItemId();
        slot.reduceCount(count);

        SlotSnapshot updatedSlot = new SlotSnapshot(
                slotIndex,
                slot.isEmpty() ? 0 : slot.getItemId(),
                slot.getCount(),
                slot.isEmpty() ? 0 : calculateExpireTime(slot)
        );

        // Execute handler
        try {
            handler.handle(new ItemEffectContext(session, itemId, config, count));
        } catch (Exception e) {
            log.error("ItemEffectHandler execution failed: itemId={}, itemType={}", itemId, config.itemType(), e);
        }

        return new UseResult(InventoryErrorCode.SUCCESS, updatedSlot);
    }

    // ─── 6.10: discardItem ──────────────────────────────────────────────────────

    public DiscardResult discardItem(GameSession session, int slotIndex, int count) {
        long userId = session.getUserId();
        PlayerInventory inventory = getOrCreateInventory(userId);
        cleanExpiredItems(session, inventory);

        // Validate slotIndex
        if (slotIndex < 0 || slotIndex >= inventory.getCapacity()) {
            return new DiscardResult(InventoryErrorCode.SLOT_INDEX_OUT_OF_RANGE, null);
        }

        Slot slot = inventory.getSlot(slotIndex);

        // Validate slot not empty
        if (slot.isEmpty()) {
            return new DiscardResult(InventoryErrorCode.SLOT_EMPTY, null);
        }

        // Check if item is expired
        ItemConfig config = itemConfigRegistry.getConfig(slot.getItemId());
        if (config != null && isSlotExpired(slot, config)) {
            slot.clear();
            return new DiscardResult(InventoryErrorCode.ITEM_EXPIRED, new SlotSnapshot(slotIndex, 0, 0, 0));
        }

        // Validate discardable
        if (config == null || !config.discardable()) {
            return new DiscardResult(InventoryErrorCode.ITEM_NOT_DISCARDABLE, null);
        }

        // Validate count
        if (count > slot.getCount()) {
            return new DiscardResult(InventoryErrorCode.INSUFFICIENT_COUNT, null);
        }

        // Deduct count
        slot.reduceCount(count);

        SlotSnapshot updatedSlot = new SlotSnapshot(
                slotIndex,
                slot.isEmpty() ? 0 : slot.getItemId(),
                slot.getCount(),
                slot.isEmpty() ? 0 : calculateExpireTime(slot)
        );

        return new DiscardResult(InventoryErrorCode.SUCCESS, updatedSlot);
    }

    // ─── Helper: isSlotExpired ───────────────────────────────────────────────────

    private boolean isSlotExpired(Slot slot, ItemConfig config) {
        ExpirationPolicy policy = config.expirationPolicy();
        if (policy == null) {
            return false;
        }
        if (policy.mode() == ExpirationMode.DURATION && policy.durationDays() <= 0) {
            return false; // never expires
        }
        long now = System.currentTimeMillis();
        if (policy.mode() == ExpirationMode.DURATION) {
            return now >= slot.getAcquiredTime() + policy.durationDays() * 86400000L;
        } else if (policy.mode() == ExpirationMode.FIXED_DATE) {
            return now >= policy.fixedExpireTime();
        }
        return false;
    }

    // ─── Stub methods for Task 8 ────────────────────────────────────────────────

    public SortResult sortInventory(GameSession session) {
        long userId = session.getUserId();
        PlayerInventory inventory = getOrCreateInventory(userId);
        cleanExpiredItems(session, inventory);

        // Collect all non-empty items: itemId → totalCount, and preserve earliest acquiredTime
        Map<Integer, Integer> itemCounts = new LinkedHashMap<>();
        Map<Integer, Long> itemAcquiredTimes = new LinkedHashMap<>();

        for (int i = 0; i < inventory.getCapacity(); i++) {
            Slot slot = inventory.getSlot(i);
            if (slot.isEmpty()) {
                continue;
            }
            int itemId = slot.getItemId();
            itemCounts.merge(itemId, slot.getCount(), Integer::sum);

            // Preserve earliest acquiredTime for items with expiration policy
            ItemConfig config = itemConfigRegistry.getConfig(itemId);
            if (config != null && config.expirationPolicy() != null) {
                long existing = itemAcquiredTimes.getOrDefault(itemId, Long.MAX_VALUE);
                if (slot.getAcquiredTime() > 0 && slot.getAcquiredTime() < existing) {
                    itemAcquiredTimes.put(itemId, slot.getAcquiredTime());
                }
            }
        }

        // If all slots empty, return empty result
        if (itemCounts.isEmpty()) {
            return new SortResult(InventoryErrorCode.SUCCESS, List.of(), inventory.getCapacity());
        }

        // Clear all slots
        for (int i = 0; i < inventory.getCapacity(); i++) {
            inventory.clearSlot(i);
        }

        // Re-fill slots compactly from index 0
        int slotIndex = 0;
        for (Map.Entry<Integer, Integer> entry : itemCounts.entrySet()) {
            int itemId = entry.getKey();
            int remaining = entry.getValue();
            ItemConfig config = itemConfigRegistry.getConfig(itemId);
            int maxStack = (config != null) ? config.maxStack() : remaining;
            boolean hasExpiration = config != null && config.expirationPolicy() != null;
            Long acquiredTime = itemAcquiredTimes.get(itemId);

            while (remaining > 0 && slotIndex < inventory.getCapacity()) {
                int toAdd = Math.min(maxStack, remaining);
                Slot slot = inventory.getSlot(slotIndex);
                slot.setItemId(itemId);
                slot.setCount(toAdd);
                if (hasExpiration && acquiredTime != null) {
                    slot.setAcquiredTime(acquiredTime);
                }
                remaining -= toAdd;
                slotIndex++;
            }
        }

        // Build SlotSnapshot list of all non-empty slots
        List<SlotSnapshot> slots = new ArrayList<>();
        for (int i = 0; i < inventory.getCapacity(); i++) {
            Slot slot = inventory.getSlot(i);
            if (!slot.isEmpty()) {
                long expireTime = calculateExpireTime(slot);
                slots.add(new SlotSnapshot(i, slot.getItemId(), slot.getCount(), expireTime));
            }
        }

        return new SortResult(InventoryErrorCode.SUCCESS, slots, inventory.getCapacity());
    }

    public SwapResult swapSlots(GameSession session, int sourceIndex, int targetIndex) {
        long userId = session.getUserId();
        PlayerInventory inventory = getOrCreateInventory(userId);

        // Validate both indices in range [0, capacity)
        if (sourceIndex < 0 || sourceIndex >= inventory.getCapacity()
                || targetIndex < 0 || targetIndex >= inventory.getCapacity()) {
            return new SwapResult(InventoryErrorCode.SLOT_INDEX_OUT_OF_RANGE, null, null);
        }

        // Swap the two Slot objects (swap all fields: itemId, count, acquiredTime)
        Slot source = inventory.getSlot(sourceIndex);
        Slot target = inventory.getSlot(targetIndex);

        int tempItemId = source.getItemId();
        int tempCount = source.getCount();
        long tempAcquiredTime = source.getAcquiredTime();

        source.setItemId(target.getItemId());
        source.setCount(target.getCount());
        source.setAcquiredTime(target.getAcquiredTime());

        target.setItemId(tempItemId);
        target.setCount(tempCount);
        target.setAcquiredTime(tempAcquiredTime);

        // Build SlotSnapshots for both slots
        SlotSnapshot sourceSnapshot = new SlotSnapshot(
                sourceIndex,
                source.isEmpty() ? 0 : source.getItemId(),
                source.getCount(),
                source.isEmpty() ? 0 : calculateExpireTime(source)
        );
        SlotSnapshot targetSnapshot = new SlotSnapshot(
                targetIndex,
                target.isEmpty() ? 0 : target.getItemId(),
                target.getCount(),
                target.isEmpty() ? 0 : calculateExpireTime(target)
        );

        return new SwapResult(InventoryErrorCode.SUCCESS, sourceSnapshot, targetSnapshot);
    }

    public ExpandResult expandCapacity(GameSession session, int amount) {
        long userId = session.getUserId();
        PlayerInventory inventory = getOrCreateInventory(userId);

        // Validate amount > 0
        if (amount <= 0) {
            return new ExpandResult(InventoryErrorCode.INVALID_PARAM, inventory.getCapacity());
        }

        // Validate capacity + amount <= MAX_CAPACITY
        if (inventory.getCapacity() + amount > MAX_CAPACITY) {
            return new ExpandResult(InventoryErrorCode.CAPACITY_LIMIT_REACHED, inventory.getCapacity());
        }

        // Expand
        inventory.expand(amount);

        return new ExpandResult(InventoryErrorCode.SUCCESS, inventory.getCapacity());
    }

    public void addItemAndNotify(GameSession session, int itemId, int count) {
        AddResult result = addItem(session.getUserId(), itemId, count);

        if (result.errorCode() == InventoryErrorCode.SUCCESS && !result.affectedSlots().isEmpty()) {
            InventoryProto.C5015_InventoryChangeNotify.Builder notifyBuilder =
                    InventoryProto.C5015_InventoryChangeNotify.newBuilder();
            for (SlotSnapshot snap : result.affectedSlots()) {
                notifyBuilder.addAffectedSlots(InventoryProto.InventorySlot.newBuilder()
                        .setSlotIndex(snap.slotIndex())
                        .setItemId(snap.itemId())
                        .setCount(snap.count())
                        .setExpireTime(snap.expireTime())
                        .build());
            }
            session.send(GameMessage.newBuilder()
                    .setMsgId(MsgId.INVENTORY.INVENTORY_CHANGE_NOTIFY)
                    .setPayload(notifyBuilder.build().toByteString())
                    .build());
        }
    }

    // ─── Result records ─────────────────────────────────────────────────────────

    public record QueryResult(int errorCode, List<SlotSnapshot> slots, int capacity) {}

    public record AddResult(int errorCode, List<SlotSnapshot> affectedSlots) {}

    public record UseResult(int errorCode, SlotSnapshot updatedSlot) {}

    public record DiscardResult(int errorCode, SlotSnapshot updatedSlot) {}

    public record SortResult(int errorCode, List<SlotSnapshot> slots, int capacity) {}

    public record SwapResult(int errorCode, SlotSnapshot sourceSlot, SlotSnapshot targetSlot) {}

    public record ExpandResult(int errorCode, int newCapacity) {}
}
