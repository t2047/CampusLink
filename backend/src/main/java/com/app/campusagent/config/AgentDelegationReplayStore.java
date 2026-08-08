package com.app.campusagent.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例开发阶段的 Delegation Token 防重放存储。
 * 生产环境横向扩容前必须替换为 Redis 等共享存储。
 */
@Component
public class AgentDelegationReplayStore {

    private final Map<String, Instant> consumed = new ConcurrentHashMap<>();

    public boolean consume(String tokenId, Instant expiresAt) {
        Instant now = Instant.now();
        consumed.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        return consumed.putIfAbsent(tokenId, expiresAt) == null;
    }
}
