package com.lowaltitude.inspection.mall.application;

/** 购物车和结算预览的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射。 */
public final class MallCartException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        NOT_PURCHASABLE,
        NOT_SOLD_IN_CITY,
        CART_FULL,
        MULTIPLE_MERCHANTS,
        ITEM_UNAVAILABLE,
        /** 暂时缺货，或要加的数量（含购物车里已有的）超过现货（cart 1.2.0）。 */
        OUT_OF_STOCK
    }

    private final Error error;

    public MallCartException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
