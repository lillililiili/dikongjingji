package com.lowaltitude.inspection.application.city;

/** 运营端改城市开通状态的业务拒绝；状态码和错误码由全局异常处理按 error 映射（契约 OpsCityErrorCode）。 */
public final class CityOperationException extends RuntimeException {

    public enum Error {
        /** 城市不存在或不可选。 */
        NOT_FOUND,
        /** 页面读到的版本已经不是当前版本：别人刚改过。 */
        VERSION_CONFLICT,
        /** 要改成的状态和现在一样。 */
        STATUS_UNCHANGED
    }

    private final Error error;

    public CityOperationException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
