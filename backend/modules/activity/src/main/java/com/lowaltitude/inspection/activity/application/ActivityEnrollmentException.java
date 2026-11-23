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
        TICKET_NOT_VALID,
        /** 收费报名（E09）：页面带来的应付金额和服务端按当前单价算的不一致，要重新读活动。 */
        AMOUNT_CHANGED,
        /** 收费报名（E09）：主办机构没有登记有效的微信收款账户。 */
        PAYMENT_UNAVAILABLE,
        /** 候补（S5-3）：这一场没满、不允许候补、或已经截止，不能排队。 */
        WAITLIST_NOT_AVAILABLE,
        /** 候补（S5-3）：有参与人已经在这一场排队。 */
        WAITLIST_ALREADY_JOINED,
        /** 候补（S5-3）：这条候补不是“已转正”（还在排队、已超时、已放弃、已结束），不能确认或付款。 */
        WAITLIST_STATE_INVALID,
        /** 改期（S5-3）：没付款、已取消、已开始、已签到，或要改去的场次不是同一活动的可报名场次。 */
        RESCHEDULE_NOT_ALLOWED,
        /** 结果记录（S5-3）：凭证没签到，不能录结果。 */
        RESULT_NOT_ALLOWED,
        /** 结果记录（S5-3）：结果刚被别人改过（版本号对不上）。 */
        RESULT_VERSION_CONFLICT
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
