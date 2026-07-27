package com.lowaltitude.inspection.infrastructure.cache;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.domain.idempotency.IdempotencyRecordPort;
import com.lowaltitude.inspection.domain.idempotency.IdempotencyRecordResult;

public final class RedisIdempotencyRecordAdapter implements IdempotencyRecordPort {

    private static final Pattern OPERATION_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private static final Pattern REQUEST_DIGEST = Pattern.compile("[a-f0-9]{64}");

    private final RedisIdempotencyGateway gateway;
    private final RedisIdempotencyProperties properties;

    public RedisIdempotencyRecordAdapter(
            RedisIdempotencyGateway gateway,
            RedisIdempotencyProperties properties
    ) {
        this.gateway = gateway;
        this.properties = properties;
    }

    @Override
    public IdempotencyRecordResult record(
            String operationCode,
            UUID actorId,
            UUID idempotencyKey,
            String requestDigest
    ) {
        requireValid(operationCode, actorId, idempotencyKey, requestDigest);
        String key = String.join(
                ":",
                properties.applicationNamespace(),
                properties.businessNamespace(),
                "idempotency",
                operationCode,
                actorId.toString(),
                idempotencyKey.toString());
        return gateway.recordAtomically(key, requestDigest, properties.idempotencyTtl());
    }

    private static void requireValid(
            String operationCode,
            UUID actorId,
            UUID idempotencyKey,
            String requestDigest
    ) {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (operationCode == null || !OPERATION_CODE.matcher(operationCode).matches()) {
            throw new IllegalArgumentException("operationCode must be a stable code");
        }
        if (requestDigest == null || !REQUEST_DIGEST.matcher(requestDigest).matches()) {
            throw new IllegalArgumentException("requestDigest must be a lowercase SHA-256");
        }
    }
}
