package com.lowaltitude.inspection.mall.application;

import com.lowaltitude.inspection.ops.common.OpsError;

/**
 * 商家资料与商家主页（S5-2）的业务拒绝，契约 contracts/openapi-mall-merchant.yaml 和
 * contracts/openapi-ops-merchant-profile.yaml。抛 OpsException，由 bootstrap 的 OpsExceptionHandler 翻成 ApiError。
 */
public enum MallMerchantError implements OpsError {
    NOT_FOUND(404, OpsError.NOT_FOUND, "商家或资料不存在"),
    VERSION_CONFLICT(409, OpsError.VERSION_CONFLICT, "资料已被别人处理过，请刷新后再试"),
    /** 这一版已经审核过（通过、驳回或被新提交的一版替换），不能再审核。 */
    REVIEW_CLOSED(409, "OPS_MERCHANT_PROFILE_REVIEW_CLOSED", "这一版资料已经处理过了");

    private final int httpStatus;
    private final String code;
    private final String message;

    MallMerchantError(int httpStatus, String code, String message) {
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
