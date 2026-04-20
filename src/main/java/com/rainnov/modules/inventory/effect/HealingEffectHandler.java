package com.rainnov.modules.inventory.effect;

import com.rainnov.modules.inventory.model.ItemType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 示例效果处理器：处理 HEALING 类型物品的使用效果。
 * 从 ItemConfig.effectParams 中读取 "healAmount" 参数，记录恢复效果日志。
 */
@Slf4j
@Component
public class HealingEffectHandler implements ItemEffectHandler {

    @Override
    public ItemType getItemType() {
        return ItemType.HEALING;
    }

    @Override
    public void handle(ItemEffectContext context) {
        String healAmount = context.itemConfig().effectParams().getOrDefault("healAmount", "0");
        log.info("执行治疗效果: userId={}, itemId={}, healAmount={}, useCount={}",
                context.session().getUserId(),
                context.itemId(),
                healAmount,
                context.useCount());
    }
}
