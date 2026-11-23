package com.lowaltitude.inspection.trade.application;

/** 运营端“收款账户登记”的业务拒绝；错误码由全局异常处理映射（契约 contracts/openapi-ops-catalog.yaml）。 */
public final class OpsMerchantAccountException extends RuntimeException {

    public enum Error {
        /** 机构不存在。 */
        NOT_FOUND,
        /** 页面读到的版本已经不是当前版本。 */
        VERSION_CONFLICT,
        /** 不是经营机构（PROVIDER）。 */
        MERCHANT_UNKNOWN,
        /** 开发环境以外不能用借微信文档示例号段的占位商户号。 */
        SUB_MCHID_PLACEHOLDER
    }

    private final Error error;

    public OpsMerchantAccountException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
