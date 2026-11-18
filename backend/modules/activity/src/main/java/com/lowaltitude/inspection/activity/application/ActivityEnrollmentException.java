package com.lowaltitude.inspection.activity.application;

/** 报名相关的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射。 */
public final class ActivityEnrollmentException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        IDEMPOTENCY_CONFLICT,
        PARTICIPANT_DUPLICATE,
        PARTICIPANT_LIMIT,
        PARTICIPANT_NOT_ELIGIBLE,
        PARTICIPANT_ALREADY_ENROLLED,
        ENROLLMENT_UNSUPPORTED,
        ENROLLMENT_NOT_OPEN,
        SESSION_FULL,
        CANCEL_NOT_ALLOWED,
        CHECKIN_NOT_OPEN,
        TICKET_NOT_VALID
    }

    private final Error error;

    public ActivityEnrollmentException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
