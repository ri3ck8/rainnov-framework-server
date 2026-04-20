package com.rainnov.modules.inventory;

public final class InventoryErrorCode {

    private InventoryErrorCode() {}

    public static final int SUCCESS = 0;
    public static final int INVENTORY_FULL = 5001;
    public static final int ITEM_NOT_FOUND = 5002;
    public static final int INVALID_PARAM = 5003;
    public static final int SLOT_EMPTY = 5004;
    public static final int INSUFFICIENT_COUNT = 5005;
    public static final int ITEM_NOT_USABLE = 5006;
    public static final int EFFECT_HANDLER_NOT_FOUND = 5007;
    public static final int ITEM_NOT_DISCARDABLE = 5008;
    public static final int SLOT_INDEX_OUT_OF_RANGE = 5009;
    public static final int CAPACITY_LIMIT_REACHED = 5010;
    public static final int ITEM_EXPIRED = 5011;
}
