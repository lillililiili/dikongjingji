package com.lowaltitude.inspection.experience.application.ops;

import com.lowaltitude.inspection.ops.common.OpsError;

/** 运营端“场馆与设备”的业务拒绝（契约 openapi-ops-venue.yaml 的 OpsVenueErrorCode）。 */
public enum VenueOpsError implements OpsError {
    NOT_FOUND(404, OpsError.NOT_FOUND, "资源不存在"),
    VERSION_CONFLICT(409, OpsError.VERSION_CONFLICT, "数据已被他人修改，请刷新后重试"),
    PUBLISH_INCOMPLETE(422, OpsError.PUBLISH_INCOMPLETE, "上线前还有必填项没填"),
    STATUS_CONFLICT(409, "OPS_VENUE_STATUS_CONFLICT", "当前状态不能做这个操作"),
    OPERATOR_INVALID(422, "OPS_VENUE_OPERATOR_INVALID", "运营机构不存在、已停用或不是服务商机构"),
    CITY_UNKNOWN(422, "OPS_CITY_UNKNOWN", "城市不在平台城市目录里"),
    PRODUCT_UNKNOWN(422, "OPS_VENUE_PRODUCT_UNKNOWN", "关联的商品不存在，或在场馆所在城市没有在售"),
    SESSION_STARTED(409, "OPS_VENUE_SESSION_STARTED", "场次已经开始，不能再改"),
    SESSION_HAS_BOOKINGS(409, "OPS_VENUE_SESSION_HAS_BOOKINGS", "场次已有预约，不能取消，只能停售"),
    TOO_MANY_SESSIONS(422, "OPS_VENUE_TOO_MANY_SESSIONS", "一次最多生成 200 场");

    private final int status;
    private final String code;
    private final String message;

    VenueOpsError(int status, String code, String message) {
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
