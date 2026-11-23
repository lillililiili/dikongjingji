package com.lowaltitude.inspection.application.catalog;

import com.lowaltitude.inspection.ops.common.OpsError;

/** 运营端“巡检服务目录”的业务拒绝（契约 openapi-ops-inspection-catalog.yaml 的 OpsInspectionCatalogErrorCode）。 */
public enum InspectionCatalogOpsError implements OpsError {
    NOT_FOUND(404, OpsError.NOT_FOUND, "资源不存在"),
    VERSION_CONFLICT(409, OpsError.VERSION_CONFLICT, "数据已被他人修改，请刷新后重试"),
    PUBLISH_INCOMPLETE(422, OpsError.PUBLISH_INCOMPLETE, "上架前还有必填项没填"),
    STATUS_UNCHANGED(409, "OPS_STATUS_UNCHANGED", "已经是这个状态"),
    PROVIDER_UNKNOWN(422, "OPS_PROVIDER_UNKNOWN", "服务商不存在、已停用或不是服务商机构"),
    CITY_UNKNOWN(422, "OPS_CITY_UNKNOWN", "城市不在平台城市目录里"),
    CATEGORY_UNKNOWN(422, "OPS_INSPECTION_CATEGORY_UNKNOWN", "类目不存在或已停用"),
    CATEGORY_CODE_TAKEN(409, "OPS_INSPECTION_CATEGORY_CODE_TAKEN", "类目编码已被占用"),
    CATEGORY_IN_USE(409, "OPS_INSPECTION_CATEGORY_IN_USE", "类目下还有上架的服务，先下架或换类目");

    private final int status;
    private final String code;
    private final String message;

    InspectionCatalogOpsError(int status, String code, String message) {
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
