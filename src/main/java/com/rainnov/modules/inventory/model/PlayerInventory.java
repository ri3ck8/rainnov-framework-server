package com.rainnov.modules.inventory.model;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PlayerInventory {

    @Getter
    private final long userId;

    @Getter
    private int capacity;

    private Slot[] slots;

    public PlayerInventory(long userId, int capacity) {
        this.userId = userId;
        this.capacity = capacity;
        this.slots = new Slot[capacity];
        for (int i = 0; i < capacity; i++) {
            this.slots[i] = new Slot();
        }
    }

    /**
     * 查找所有包含指定 itemId 且未满堆叠的 Slot 索引
     */
    public List<Integer> findStackableSlots(int itemId, int maxStack) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            Slot slot = slots[i];
            if (!slot.isEmpty() && slot.getItemId() == itemId && slot.getCount() < maxStack) {
                result.add(i);
            }
        }
        return result;
    }

    /**
     * 查找第一个空 Slot 索引，无空位返回 -1
     */
    public int findFirstEmptySlot() {
        for (int i = 0; i < capacity; i++) {
            if (slots[i].isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 计算可用空 Slot 数量
     */
    public int countEmptySlots() {
        int count = 0;
        for (int i = 0; i < capacity; i++) {
            if (slots[i].isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取指定索引的 Slot
     */
    public Slot getSlot(int index) {
        return slots[index];
    }

    /**
     * 设置指定索引的 Slot
     */
    public void setSlot(int index, Slot slot) {
        slots[index] = slot;
    }

    /**
     * 清空指定索引的 Slot
     */
    public void clearSlot(int index) {
        slots[index].clear();
    }

    /**
     * 扩容：增加 capacity，创建新数组，复制已有 Slot，新位置填充空 Slot
     */
    public void expand(int amount) {
        int newCapacity = capacity + amount;
        Slot[] newSlots = Arrays.copyOf(slots, newCapacity);
        for (int i = capacity; i < newCapacity; i++) {
            newSlots[i] = new Slot();
        }
        this.slots = newSlots;
        this.capacity = newCapacity;
    }

    /**
     * 获取所有非空 Slot 的快照
     */
    public List<SlotSnapshot> getNonEmptySlots() {
        List<SlotSnapshot> result = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            Slot slot = slots[i];
            if (!slot.isEmpty()) {
                result.add(new SlotSnapshot(i, slot.getItemId(), slot.getCount(), slot.getAcquiredTime()));
            }
        }
        return result;
    }
}
