package com.lowaltitude.inspection.experience.application;

/** 核销工作台（S3-3）的业务拒绝；对外的状态码和错误码由全局异常处理按 reason 映射。 */
public final class VenueCheckinException extends RuntimeException {

    public enum Reason {
        /** 没有这个核销码，或这条记录不是这个操作员的。 */
        NOT_FOUND,
        /** 不是服务商操作员，或令牌里没有机构。 */
        FORBIDDEN,
        /** 预约是当前场次的，但还没到签到开放时间。 */
        NOT_OPEN,
        /** 这条记录不能这样处理（核销成功的记录、或这种异常没有这个处理方式）。 */
        ACTION_NOT_ALLOWED,
        /** 这条异常已经用别的处理方式登记过。 */
        ALREADY_RESOLVED,
        /** 登记放行时预约已经变了、当前场次不在核销时间或不是同一个项目、或有人在当前场次另有预约。 */
        RELEASE_NOT_ALLOWED,
        /** 登记放行时当前场次没有余位。 */
        SEATS_NOT_ENOUGH
    }

    private final Reason reason;

    public VenueCheckinException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
