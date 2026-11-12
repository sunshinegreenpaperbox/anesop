package com.anesop.api.security;

import com.anesop.m0.api.security.AccessTokenRevocationStore;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 访问令牌吊销表的内存实现（S0-07）。
 *
 * <p>登出后访问令牌立即失效；记录只需保留到令牌自然过期。单实例部署足够；
 * 多实例部署时替换为 Redis 实现（key=jti，TTL=剩余有效期），接口
 * {@link AccessTokenRevocationStore} 不变（见 docs/modules/m0/README.md）。
 */
@Component
public class InMemoryAccessTokenRevocationStore implements AccessTokenRevocationStore {

    private final Map<String, Instant> revokedUntil = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryAccessTokenRevocationStore(Clock authClock) {
        this.clock = authClock;
    }

    @Override
    public void revoke(String tokenId, Instant expiresAt) {
        if (tokenId == null || expiresAt == null) {
            return;
        }
        revokedUntil.put(tokenId, expiresAt);
        purgeExpired();
    }

    @Override
    public boolean isRevoked(String tokenId) {
        if (tokenId == null) {
            return false;
        }
        Instant expiresAt = revokedUntil.get(tokenId);
        if (expiresAt == null) {
            return false;
        }
        if (!expiresAt.isAfter(Instant.now(clock))) {
            revokedUntil.remove(tokenId);
            return false;
        }
        return true;
    }

    private void purgeExpired() {
        Instant now = Instant.now(clock);
        revokedUntil.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }
}
