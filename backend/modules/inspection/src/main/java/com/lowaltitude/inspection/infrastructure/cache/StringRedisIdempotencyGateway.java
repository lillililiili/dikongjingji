package com.lowaltitude.inspection.infrastructure.cache;

import java.time.Duration;
import java.util.List;

import com.lowaltitude.inspection.domain.idempotency.IdempotencyRecordResult;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class StringRedisIdempotencyGateway implements RedisIdempotencyGateway {

    private static final DefaultRedisScript<Long> RECORD_SCRIPT = new DefaultRedisScript<>(
            """
            local existing = redis.call('GET', KEYS[1])
            if not existing then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return 1
            end
            if existing == ARGV[1] then
              return 2
            end
            return 3
            """,
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public StringRedisIdempotencyGateway(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public IdempotencyRecordResult recordAtomically(String key, String requestDigest, Duration ttl) {
        Long result = redisTemplate.execute(
                RECORD_SCRIPT,
                List.of(key),
                requestDigest,
                Long.toString(ttl.toMillis()));
        if (result == null) {
            throw new IllegalStateException("Redis idempotency script returned no result");
        }
        return switch (result.intValue()) {
            case 1 -> IdempotencyRecordResult.ACQUIRED;
            case 2 -> IdempotencyRecordResult.REPLAY;
            case 3 -> IdempotencyRecordResult.CONFLICT;
            default -> throw new IllegalStateException("Redis idempotency script returned an unknown result");
        };
    }
}
