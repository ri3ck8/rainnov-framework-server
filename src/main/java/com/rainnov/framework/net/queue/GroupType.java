package com.rainnov.framework.net.queue;

/**
 * 消息串行消费维度枚举。
 * 决定消息投入哪种队列进行串行消费。
 */
public enum GroupType {
    /** 默认：按用户队列串行 */
    USER,
    /** 按队伍串行（单进程内） */
    TEAM,
    /** 按公会串行（单进程内） */
    GUILD,
    /** 按队伍串行（跨进程，Redis） */
    TEAM_DISTRIBUTED,
    /** 按公会串行（跨进程，Redis） */
    GUILD_DISTRIBUTED,
}
