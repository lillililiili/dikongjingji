package com.lowaltitude.inspection.application.reservation;

public final class TimeSlotFullException extends RuntimeException {

    public TimeSlotFullException() {
        super("inspection time slot is full");
    }
}
