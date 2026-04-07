package com.rainnov.framework.net.queue;

import com.rainnov.framework.net.session.GameSession;

/**
 * 从 GameSession 中解析 groupKey 的业务层接口。
 * 业务层实现并注册为 Spring Bean。
 */
public interface GroupKeyResolver {

    /**
     * 从 session 中解析 groupKey。
     *
     * @param session   当前会话
     * @param groupType 串行消费维度
     * @return groupKey 字符串（如 "team:123"、"guild:456"）；
     *         返回 null 表示无法解析，消息降级到用户队列
     */
    String resolve(GameSession session, GroupType groupType);
}
