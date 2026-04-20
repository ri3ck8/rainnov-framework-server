package com.rainnov.modules.inventory.model;

public record ExpirationPolicy(
    ExpirationMode mode,
    int durationDays,
    long fixedExpireTime
) {}
