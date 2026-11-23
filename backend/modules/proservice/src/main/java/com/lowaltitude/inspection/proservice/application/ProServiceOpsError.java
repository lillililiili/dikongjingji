package com.lowaltitude.inspection.proservice.application;

import com.lowaltitude.inspection.ops.common.OpsError;

/** 运营端“专业服务目录”的业务拒绝（契约 openapi-ops-proservice.yaml 的 OpsProServiceErrorCode）。 */
public enum ProServiceOpsError implements OpsError {
    NOT_FOUND(404, OpsError.NOT_FOUND, "资源不存在"),
    VERSION_CONFLICT(409, OpsError.VERSION_CONFLICT, "数据已被他人修改，请刷新后重试"),
    PUBLISH_INCOMPLETE(422, OpsError.PUBLISH_INCOMPLETE, "上架前还有必填项没填"),
    STATUS_UNCHANGED(409, "OPS_STATUS_UNCHANGED", "已经是这个状态"),
    PROVIDER_UNKNOWN(422, "OPS_PROVIDER_UNKNOWN", "服务商不存在、已停用或不是服务商机构"),
    CITY_UNKNOWN(422, "OPS_CITY_UNKNOWN", "城市不在平台城市目录里");

    private final int status;
    private final String code;
    private final String message;

    ProServiceOpsError(int status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public int httpStatus() {
        return status;
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
