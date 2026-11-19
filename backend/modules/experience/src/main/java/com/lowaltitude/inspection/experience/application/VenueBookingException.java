package com.lowaltitude.inspection.experience.application;

/** 到馆预约的业务拒绝（E10）；对外的状态码和错误码由全局异常处理按 error 映射（契约 VenueErrorCode）。 */
public final class VenueBookingException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        IDEMPOTENCY_CONFLICT,
        /** 场次已开始、已取消，或项目、场馆已下线。 */
        SESSION_NOT_OPEN,
        /** 有参与人在场次当天不满项目的最低年龄。 */
        PARTICIPANT_NOT_ELIGIBLE,
        /** 有参与人在这一场已有有效（或待付款）的预约。 */
        PARTICIPANT_ALREADY_BOOKED,
        /** 余位不够这次的人数。 */
        SEATS_NOT_ENOUGH,
        /** 页面带来的应付金额和服务端按当前单价算的不一致。 */
        AMOUNT_CHANGED,
        /** 要付款的项目，场馆运营机构没有登记有效的微信收款账户。 */
        PAYMENT_UNAVAILABLE,
        /** 改期时页面看到的预约版本已经过时。 */
        VERSION_CONFLICT,
        RESCHEDULE_NOT_ALLOWED,
        CANCEL_NOT_ALLOWED,
        CHECKIN_NOT_OPEN,
        /** 核销码已作废（预约已取消）。 */
        CODE_NOT_VALID
    }

    private final Error error;

    public VenueBookingException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
