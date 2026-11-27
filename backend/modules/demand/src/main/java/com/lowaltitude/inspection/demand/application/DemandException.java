package com.lowaltitude.inspection.demand.application;

/** 需求和报价的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射（契约 DemandErrorCode）。 */
public final class DemandException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        IDEMPOTENCY_CONFLICT,
        /** 提交时缺了必填项或填得不对；field 指出第一处（页面从上到下的顺序）。 */
        INCOMPLETE,
        /** 需求现在的状态不能做这个操作（比如改已提交的需求、取消已选定的需求）。 */
        STATE_INVALID,
        BOOKING_CONFLICT,
        BOOKING_SERVICE_UNAVAILABLE,
        /** 页面看到的需求版本已经过时。 */
        VERSION_CONFLICT,
        /** 报价截止时间已过，不再收新报价、不能改价。 */
        QUOTING_CLOSED,
        /** 选定时带的报价版本不是最新的，或报价已撤回。 */
        QUOTE_CHANGED,
        /** 报价已过有效期，不能选。 */
        QUOTE_EXPIRED,
        /** 报价已被选中或需求已结束，不能再改、不能撤回。 */
        QUOTE_LOCKED
    }

    private final Error error;
    private final String field;

    public DemandException(Error error) {
        this(error, null);
    }

    public DemandException(Error error, String field) {
        super(field == null ? error.name() : error.name() + ":" + field);
        this.error = error;
        this.field = field;
    }

    public Error error() {
        return error;
    }

    /** INCOMPLETE 时是第一处有问题的字段（请求里的字段名），其余为 null。 */
    public String field() {
        return field;
    }
}
