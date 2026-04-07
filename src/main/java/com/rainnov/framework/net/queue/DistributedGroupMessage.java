package com.rainnov.framework.net.queue;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 可序列化的 GroupMessage DTO，用于 Redis 存储。
 * 由于 GroupMessage 包含 GameSession（含 Channel/Thread 等不可序列化字段），
 * 需要提取可序列化的元信息用于 Redis JSON 存储。
 *
 * 跨节点消费时，消费节点可能不持有原始 Session，此场景标记为 TODO。
 */
public record DistributedGroupMessage(
        @JsonProperty("sessionId") String sessionId,
        @JsonProperty("userId") long userId,
        @JsonProperty("messageBytes") byte[] messageBytes
) {
    @JsonCreator
    public DistributedGroupMessage {
    }
}
