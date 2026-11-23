package com.lowaltitude.inspection.support.application;

/** 客服会话和反馈的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射（契约 SupportErrorCode）。 */
public final class SupportException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        /** 同一个幂等键已经用在了内容不同的请求上。 */
        IDEMPOTENCY_CONFLICT,
        /** 对方机构不存在、已停用，或不是商家、服务商这类机构。 */
        COUNTERPART_UNAVAILABLE,
        /** 运营端：反馈已经处理过了（只处理一次）。 */
        ALREADY_HANDLED,
        /** 运营端：页面读到的版本已经不是当前版本。 */
        VERSION_CONFLICT
    }

    private final Error error;

    public SupportException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
