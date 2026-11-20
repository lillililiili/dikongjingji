package com.lowaltitude.inspection.content.application;

/** 视频互动（1.2.0）的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射。不存在用 ContentNotFoundException。 */
public final class ContentException extends RuntimeException {

    public enum Error {
        /** 要登录（关注流）。 */
        UNAUTHORIZED,
        /** 没有这个受权接口要的角色。 */
        FORBIDDEN,
        /** 同一个幂等键已经用在了内容不同的评论上。 */
        IDEMPOTENCY_CONFLICT,
        /** 评论命中关键词过滤。 */
        COMMENT_BLOCKED,
        /** 同一账号发评论太快。 */
        COMMENT_TOO_FREQUENT
    }

    private final Error error;

    public ContentException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
