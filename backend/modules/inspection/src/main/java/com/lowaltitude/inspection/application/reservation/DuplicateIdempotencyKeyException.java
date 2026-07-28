package com.lowaltitude.inspection.application.reservation;

final class DuplicateIdempotencyKeyException extends RuntimeException {

    DuplicateIdempotencyKeyException() {
        super("idempotency key already exists");
    }
}
