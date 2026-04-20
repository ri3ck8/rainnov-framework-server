package com.rainnov.framework.net.queue;

import com.rainnov.framework.net.session.GameSession;
import org.springframework.stereotype.Component;

/**
 * GroupKeyResolver 示例实现。
 * 从 GameSession 中解析 teamId / guildId 作为 groupKey。
 */
@Component
public class GameGroupKeyResolver implements GroupKeyResolver {

    @Override
    public String resolve(GameSession session, GroupType groupType) {
        return switch (groupType) {
            case TEAM, TEAM_DISTRIBUTED -> {
                Long teamId = session.getTeamId();
                yield teamId != null ? "team:" + teamId : null;
            }
            case GUILD, GUILD_DISTRIBUTED -> {
                Long guildId = session.getGuildId();
                yield guildId != null ? "guild:" + guildId : null;
            }
            default -> null;
        };
    }
}
