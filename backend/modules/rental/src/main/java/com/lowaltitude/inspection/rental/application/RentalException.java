package com.lowaltitude.inspection.rental.application;

/** 租赁的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射（契约 RentalErrorCode）。 */
public final class RentalException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        IDEMPOTENCY_CONFLICT,
        /** 这个供给现在不能租：下架、不是租赁商品，或不在使用地点所在的城市出租。 */
        OFFER_UNAVAILABLE,
        /** 出租方没有登记有效的收款账户，不能在线付租金。 */
        PAYMENT_UNAVAILABLE,
        /** 选的使用地点（收货地址）不存在或不是自己的。 */
        ADDRESS_NOT_FOUND,
        /** 询价单或租赁订单现在的状态不能做这个操作。 */
        STATE_INVALID,
        /** 页面看到的询价单版本已经过时。 */
        VERSION_CONFLICT,
        /** 确认时带的报价版本不是最新的。 */
        QUOTE_CHANGED,
        /** 报价已过有效期。 */
        QUOTE_EXPIRED,
        /** 商家报价时说这段时间不能出租。 */
        SCHEDULE_UNAVAILABLE,
        /** 起租日已经过了，要重新询价。 */
        START_DATE_PASSED,
        /** 没有勾选“我已核对租期、费用与合同条款”。 */
        TERMS_NOT_AGREED,
        /** 交易模块不能为这张单退款（比如已经不是已付款）。 */
        REFUND_NOT_ALLOWED
    }

    private final Error error;

    public RentalException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
