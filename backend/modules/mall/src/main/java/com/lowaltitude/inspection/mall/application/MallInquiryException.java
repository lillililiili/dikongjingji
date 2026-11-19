package com.lowaltitude.inspection.mall.application;

/** 询价的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射（契约 MallInquiryErrorCode）。 */
public final class MallInquiryException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        IDEMPOTENCY_CONFLICT,
        /** 选的供给不是这件询价商品在这个城市的在售供给。 */
        OFFER_UNAVAILABLE,
        /** 选的商家没有登记有效的收款账户，选定后没法下单付款。 */
        MERCHANT_UNAVAILABLE,
        /** 询价单（或这家的邀请）现在的状态不能做这个操作。 */
        STATE_INVALID,
        /** 报价截止时间已过，不再收报价、不能改价。 */
        QUOTING_CLOSED,
        /** 页面看到的询价单版本已经过时。 */
        VERSION_CONFLICT,
        /** 选定时带的报价版本不是最新的，或报价已撤回、已失效。 */
        QUOTE_CHANGED,
        /** 报价已过有效期。 */
        QUOTE_EXPIRED,
        /** 下单用的报价不是这张询价单选定的那一份。 */
        QUOTE_NOT_CHOSEN
    }

    private final Error error;

    public MallInquiryException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
