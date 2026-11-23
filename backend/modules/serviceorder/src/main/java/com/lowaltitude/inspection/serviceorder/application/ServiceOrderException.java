package com.lowaltitude.inspection.serviceorder.application;

/** 服务订单的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射（契约 ServiceOrderErrorCode）。 */
public final class ServiceOrderException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        /** 现在的状态不能做这个操作。 */
        STATE_INVALID,
        /** If-Match 的订单版本已经过时。 */
        VERSION_CONFLICT,
        /** 确认的方案不是最新一版。 */
        PLAN_CHANGED,
        /** 验收或退回的不是最近一次待验收的交付。 */
        DELIVERY_CHANGED,
        /** 没有勾选“我已核对方案的服务范围、金额与交付约定”。 */
        TERMS_NOT_AGREED,
        /** 服务商没有登记有效的收款账户。 */
        PAYMENT_UNAVAILABLE,
        /** 交易模块不能为这张单退款。 */
        REFUND_NOT_ALLOWED,
        /** 文件类型、大小、个数不对，或交付时列的文件不对。 */
        FILE_INVALID,
        /** 文件存储不可用。 */
        FILE_STORE_UNAVAILABLE
    }

    private final Error error;

    public ServiceOrderException(Error error) {
        super(error.name());
        this.error = error;
    }

    public ServiceOrderException(Error error, Throwable cause) {
        super(error.name(), cause);
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
