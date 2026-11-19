package com.lowaltitude.inspection.trade.application;

/**
 * 渠道调用失败。消息里只放渠道的错误码和简短说明，不放请求体、密钥或用户信息。
 */
public class PaymentChannelException extends RuntimeException {

    public enum Kind {
        /** 网络不通、超时、渠道 5xx、SYSTEM_ERROR / BANK_ERROR / FREQUENCY_LIMITED：结果不确定，用相同参数（同一个单号）重试或查单。 */
        RETRYABLE,
        /** 渠道明确拒绝（参数、权限、余额等），code 是渠道的错误码；换参数前重试没有用。 */
        REJECTED,
        /** 应答或回调验签不过、格式不对：内容不能信，不能据此改任何状态。 */
        UNTRUSTED
    }

    private final Kind kind;
    private final String code;
    private final int httpStatus;

    public PaymentChannelException(Kind kind, String code, int httpStatus, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public PaymentChannelException(Kind kind, String code, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.code = code;
        this.httpStatus = 0;
    }

    public Kind kind() {
        return kind;
    }

    /** 渠道错误码（如 ORDER_NOT_EXIST、NOT_ENOUGH）；本地原因时是 NETWORK、SIGNATURE、FORMAT 之类。 */
    public String code() {
        return code;
    }

    /** 渠道应答的 HTTP 状态；没有应答时为 0。 */
    public int httpStatus() {
        return httpStatus;
    }
}
