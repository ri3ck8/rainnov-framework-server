package com.rainnov.modules.inventory.model;

public record SlotSnapshot(
    int slotIndex,
    int itemId,
    int count,
    long expireTime
) {}
