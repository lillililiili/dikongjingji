package com.lowaltitude.inspection.application.reservation;

public final class ReservationCancellationNotAllowedException extends RuntimeException {
    public ReservationCancellationNotAllowedException() {
        super("reservation cannot be cancelled in its current state");
    }
}
