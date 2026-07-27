package com.lowaltitude.inspection.domain.idempotency;

public enum IdempotencyRecordResult {
    ACQUIRED,
    REPLAY,
    CONFLICT
}
