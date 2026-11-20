package com.lowaltitude.inspection.live.application;

/** 直播的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射（契约 LiveErrorCode）。 */
public final class LiveException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        /** 同一个幂等键已经用在了内容不同的请求上。 */
        IDEMPOTENCY_CONFLICT,
        /** 已经开播或结束（设提醒、改预告）。 */
        NOT_SCHEDULED,
        /** 不在直播中（发弹幕、点赞）。 */
        NOT_LIVE,
        /** 已经结束（改讲解清单）。 */
        ENDED,
        /** 弹幕没有通过关键词过滤。 */
        COMMENT_BLOCKED,
        /** 同一账号两条弹幕之间太近。 */
        COMMENT_TOO_FAST,
        /** 没有可用的直播服务，建不了流。 */
        STREAM_UNAVAILABLE,
        /** 服务商回调的签名不对、过期，或没有可用的直播服务。 */
        CALLBACK_REJECTED
    }

    private final Error error;

    public LiveException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
