package com.lowaltitude.inspection.infrastructure.cache;

import java.time.Duration;

public interface RedisIdempotencyGateway {

    boolean putIfAbsent(String key, String requestDigest, Duration ttl);

    String get(String key);
}
