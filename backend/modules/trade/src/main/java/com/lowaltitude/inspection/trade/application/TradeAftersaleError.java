package com.lowaltitude.inspection.trade.application;

import com.lowaltitude.inspection.ops.common.OpsError;

/**
 * 商品售后（S4-2）的业务拒绝，契约 contracts/openapi-trade-aftersale.yaml 的 AftersaleErrorCode。
 * 抛 {@link com.lowaltitude.inspection.ops.common.OpsException}，由 bootstrap 的 OpsExceptionHandler 统一翻成 ApiError，
 * 不改全局异常处理（交易模块原有的 TradeException 不变）。
 */
public enum TradeAftersaleError implements OpsError {
    NOT_FOUND(404, OpsError.NOT_FOUND, "售后单或订单不存在"),
    /** 这张单现在不能申请这一类售后、或这个原因（details.reason 说明为什么）。 */
    NOT_ELIGIBLE(409, "TRADE_AFTERSALE_NOT_ELIGIBLE", "这笔订单现在不能申请这种售后"),
    /** 已有一张没结束的售后单。 */
    ALREADY_OPEN(409, "TRADE_AFTERSALE_ALREADY_OPEN", "这笔订单已有处理中的售后"),
    /** 申请金额超过还能退的钱。 */
    AMOUNT_EXCEEDS(409, "TRADE_AFTERSALE_AMOUNT_EXCEEDS", "申请金额超过可退金额"),
    /** 售后单当前状态不允许这个操作。 */
    STATE_INVALID(409, "TRADE_AFTERSALE_STATE_INVALID", "售后单当前状态不允许这个操作"),
    VERSION_CONFLICT(412, "TRADE_AFTERSALE_VERSION_CONFLICT", "售后单已更新，请刷新后重试"),
    IDEMPOTENCY_CONFLICT(409, "TRADE_IDEMPOTENCY_KEY_REUSED", "幂等键已用于不同请求"),
    /** 同意退货时没填寄回地址，商家也没设默认寄回地址。 */
    RETURN_ADDRESS_REQUIRED(422, "TRADE_AFTERSALE_RETURN_ADDRESS_REQUIRED", "请填写寄回地址"),
    /** 凭证图片不是自己上传的、已经用过、类型或大小不对、数量超过上限。 */
    IMAGE_INVALID(422, "TRADE_AFTERSALE_IMAGE_INVALID", "凭证图片无效"),
    /** 一小时内上传太多。 */
    UPLOAD_LIMITED(429, "TRADE_AFTERSALE_UPLOAD_LIMITED", "上传太频繁，请稍后再试"),
    /** 对象存储暂时不可用。 */
    STORAGE_UNAVAILABLE(503, "TRADE_AFTERSALE_STORAGE_UNAVAILABLE", "图片存储暂不可用，请稍后再试"),
    /** 支付渠道暂不可用，退款稍后再试。 */
    CHANNEL_UNAVAILABLE(503, "TRADE_CHANNEL_UNAVAILABLE", "微信支付暂不可用，请稍后再试");

    private final int httpStatus;
    private final String code;
    private final String message;

    TradeAftersaleError(int httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
