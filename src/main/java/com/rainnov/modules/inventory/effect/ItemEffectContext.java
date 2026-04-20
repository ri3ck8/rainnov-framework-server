package com.rainnov.modules.inventory.effect;

import com.rainnov.modules.inventory.model.ItemConfig;
import com.rainnov.framework.net.session.GameSession;

public record ItemEffectContext(
    GameSession session,
    int itemId,
    ItemConfig itemConfig,
    int useCount
) {}
