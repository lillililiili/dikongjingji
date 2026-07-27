package com.lowaltitude.inspection.domain.idempotency;

import java.util.UUID;

public interface IdempotencyRecordPort {

    IdempotencyRecordResult record(
            String operationCode,
            UUID actorId,
            UUID idempotencyKey,
            String requestDigest
    );
}
