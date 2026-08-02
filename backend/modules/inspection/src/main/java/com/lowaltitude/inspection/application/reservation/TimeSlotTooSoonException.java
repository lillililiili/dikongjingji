package com.lowaltitude.inspection.application.reservation;

public final class TimeSlotTooSoonException extends RuntimeException {

    public TimeSlotTooSoonException() {
        super("inspection time slot requires one hour lead time");
    }
}
