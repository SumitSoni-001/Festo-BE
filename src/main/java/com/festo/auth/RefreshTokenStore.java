package com.festo.auth;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

// refresh:<user_id> -> refresh token, 7 day TTL - matches DB Design 1.3
@Service
public class RefreshTokenStore {

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RefreshTokenStore(StringRedisTemplate redis, @Value("${jwt.refresh-token-expiry-days}") long days) {
        this.redis = redis;
        this.ttl = Duration.ofDays(days);
    }

    public void store(Long userId, String refreshToken) {
        redis.opsForValue().set(key(userId), refreshToken, ttl);
    }

    public boolean matches(Long userId, String refreshToken) {
        return refreshToken.equals(redis.opsForValue().get(key(userId)));
    }

    public void delete(Long userId) {
        redis.delete(key(userId));
    }

    private String key(Long userId) {
        return "refresh:" + userId;
    }
}
