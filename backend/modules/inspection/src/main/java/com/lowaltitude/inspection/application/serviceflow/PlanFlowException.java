package com.lowaltitude.inspection.application.serviceflow;

public class PlanFlowException extends RuntimeException {

    private final PlanFlowError error;

    PlanFlowException(PlanFlowError error) {
        super(error.name());
        this.error = error;
    }

    public PlanFlowError error() {
        return error;
    }

    public enum PlanFlowError {
        INVALID_ARGUMENT,
        FORBIDDEN,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        VERSION_CONFLICT,
        RESERVATION_STATE_INVALID,
        PLAN_NOT_PUBLISHED,
        PLAN_EXPIRED,
        PLAN_ALREADY_CONFIRMED,
        ORDER_STATE_INVALID,
        DELIVERY_STATE_INVALID,
        DELIVERY_FILES_INVALID
    }
}
