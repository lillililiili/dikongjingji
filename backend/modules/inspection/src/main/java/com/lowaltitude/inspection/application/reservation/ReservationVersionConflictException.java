package com.lowaltitude.inspection.application.reservation;

public final class ReservationVersionConflictException extends RuntimeException {
    public ReservationVersionConflictException() {
        super("reservation version is stale");
    }
}
