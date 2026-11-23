package com.lowaltitude.inspection.trade.application;

/** 交易模块的业务拒绝；对外的状态码和错误码由全局异常处理按 error 映射（契约 TradeErrorCode）。 */
public final class TradeException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        IDEMPOTENCY_CONFLICT,
        AMOUNT_CHANGED,
        ADDRESS_REQUIRED,
        DELIVERY_UNAVAILABLE,
        MERCHANT_PAYMENT_UNAVAILABLE,
        ORDER_STATE_INVALID,
        VERSION_CONFLICT,
        /** 订单已过付款截止时间，不能再发起支付。 */
        ORDER_EXPIRED,
        /** 支付渠道没有配置，或这次调用失败、结果不确定；稍后再试。 */
        CHANNEL_UNAVAILABLE,
        /** 订单有进行中的支付单，关单前要先向渠道查单。只在交易模块内部流转，由支付服务接手。 */
        PAYMENT_IN_PROGRESS,
        /** 已付款的订单取消要退款。只在交易模块内部流转，由退款服务接手。 */
        REFUND_REQUIRED,
        /** 取货码不对，或输错太多次已锁定。 */
        PICKUP_CODE_INVALID,
        /** 退款金额超过还能退的钱。 */
        REFUND_EXCEEDS_PAID,
        /** 一张订单最多退 50 次，或已过可退款的期限（付款后一年）。 */
        REFUND_LIMIT_REACHED,
        /** 这一天的账单还没全部导入（微信 10 点后才出账单，或导入失败）。 */
        RECONCILIATION_NOT_READY,
        /** 这份询价报价已经下过一张还没关闭的订单（F4：一份报价同时只能有一张订单）。 */
        QUOTE_ALREADY_ORDERED,
        /** 账号在注销冷静期（S5-8），不能下单和付款；撤回注销后恢复。 */
        ACCOUNT_CLOSING
    }

    private final Error error;

    public TradeException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
