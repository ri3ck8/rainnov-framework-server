package com.rainnov.modules.inventory.model;

import java.util.Map;

public record ItemConfig(
    int itemId,
    String name,
    ItemType itemType,
    int maxStack,
    boolean usable,
    boolean discardable,
    Map<String, String> effectParams,
    ExpirationPolicy expirationPolicy  // 可为 null，null 表示永不过期
) {}
