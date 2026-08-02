package com.lowaltitude.inspection.application.reservation;

public final class ReservationAccessDeniedException extends RuntimeException {
    public ReservationAccessDeniedException(String message) {
        super(message);
    }
}
