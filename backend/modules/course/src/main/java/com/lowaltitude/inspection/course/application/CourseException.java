package com.lowaltitude.inspection.course.application;

import java.util.Map;

/** 课程的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射（契约 CourseErrorCode）。 */
public final class CourseException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        /** 同一个幂等键已经用在了内容不同的请求上。 */
        IDEMPOTENCY_CONFLICT,
        /** 没上架、不单独出售或是线下班。 */
        NOT_PURCHASABLE,
        /** 开课机构没有登记在线收款账户。 */
        PAYMENT_UNAVAILABLE,
        /** 已经开通着这门课。 */
        ALREADY_OWNED,
        /** 价格变了，和页面上看到的不一样。 */
        AMOUNT_CHANGED,
        /** 待付款、已关闭、已退款或已过期，不能学。 */
        NOT_LEARNABLE,
        /** 还没拿过这一节的播放地址就说播完了。 */
        LESSON_NOT_STARTED,
        /** 不能在线退款；details.reason 是 CourseRefundBlock。 */
        REFUND_NOT_ALLOWED
    }

    private final Error error;
    private final transient Map<String, Object> details;

    public CourseException(Error error) {
        this(error, null);
    }

    public CourseException(Error error, Map<String, Object> details) {
        super(error.name());
        this.error = error;
        this.details = details == null ? null : Map.copyOf(details);
    }

    public Error error() {
        return error;
    }

    /** 放进 ApiError.details；没有时为 null。 */
    public Map<String, Object> details() {
        return details;
    }
}
