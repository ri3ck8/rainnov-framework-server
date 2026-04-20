package com.rainnov.modules.inventory.effect;

import com.rainnov.modules.inventory.model.ItemType;

public interface ItemEffectHandler {

    /** 声明处理的物品类型 */
    ItemType getItemType();

    /** 执行物品效果 */
    void handle(ItemEffectContext context);
}
