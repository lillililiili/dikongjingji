package com.lowaltitude.inspection.favorite.application;

/** 收藏的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射（契约 FavoriteErrorCode）。 */
public final class FavoriteException extends RuntimeException {

    public enum Error {
        FORBIDDEN,
        /** 对象不存在、已下架或当前城市不提供，不能收藏。 */
        TARGET_UNAVAILABLE,
        /** 收藏已到上限。 */
        LIMIT_REACHED
    }

    private final Error error;

    public FavoriteException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
