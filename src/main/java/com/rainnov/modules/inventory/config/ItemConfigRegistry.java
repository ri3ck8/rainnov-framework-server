package com.rainnov.modules.inventory.config;

import com.rainnov.modules.inventory.model.ExpirationMode;
import com.rainnov.modules.inventory.model.ExpirationPolicy;
import com.rainnov.modules.inventory.model.ItemConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ItemConfigRegistry {

    private final Map<Integer, ItemConfig> configs = new HashMap<>();

    public void register(ItemConfig config) {
        configs.put(config.itemId(), config);
    }

    public ItemConfig getConfig(int itemId) {
        return configs.get(itemId);
    }

    public void validateConfigs() {
        for (ItemConfig config : configs.values()) {
            ExpirationPolicy policy = config.expirationPolicy();
            if (policy != null
                    && policy.mode() == ExpirationMode.DURATION
                    && policy.durationDays() <= 0) {
                log.warn("ItemConfig [itemId={}, name={}] has DURATION expiration with durationDays={}, treating as never expires",
                        config.itemId(), config.name(), policy.durationDays());
            }
        }
    }
}
