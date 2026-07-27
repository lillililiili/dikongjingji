package com.lowaltitude.inspection.infrastructure.cache;

import java.time.Duration;

import com.lowaltitude.inspection.domain.idempotency.IdempotencyRecordResult;

public interface RedisIdempotencyGateway {

    IdempotencyRecordResult recordAtomically(String key, String requestDigest, Duration ttl);
}
