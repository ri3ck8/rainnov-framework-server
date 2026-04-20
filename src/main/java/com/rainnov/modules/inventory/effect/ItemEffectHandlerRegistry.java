package com.rainnov.modules.inventory.effect;

import com.rainnov.modules.inventory.model.ItemType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ItemEffectHandlerRegistry implements InitializingBean {

    private final List<ItemEffectHandler> handlers;
    private final Map<ItemType, ItemEffectHandler> handlerMap = new EnumMap<>(ItemType.class);

    public ItemEffectHandlerRegistry(List<ItemEffectHandler> handlers) {
        this.handlers = handlers;
    }

    @Override
    public void afterPropertiesSet() {
        for (ItemEffectHandler handler : handlers) {
            ItemType itemType = handler.getItemType();
            if (handlerMap.containsKey(itemType)) {
                throw new IllegalStateException(
                    "Duplicate ItemEffectHandler registration for ItemType: " + itemType
                    + ". Existing: " + handlerMap.get(itemType).getClass().getName()
                    + ", Duplicate: " + handler.getClass().getName()
                );
            }
            handlerMap.put(itemType, handler);
            log.info("Registered ItemEffectHandler for {}: {}", itemType, handler.getClass().getSimpleName());
        }
    }

    /** 根据 ItemType 查找 Handler，不存在返回 null */
    public ItemEffectHandler getHandler(ItemType itemType) {
        return handlerMap.get(itemType);
    }
}
