package com.lowaltitude.inspection.activity.application.ops;

import com.lowaltitude.inspection.ops.common.OpsError;

/** 运营端“活动与场次”的业务拒绝（契约 openapi-ops-activity.yaml 的 OpsActivityErrorCode）。 */
public enum ActivityOpsError implements OpsError {
    NOT_FOUND(404, OpsError.NOT_FOUND, "资源不存在"),
    VERSION_CONFLICT(409, OpsError.VERSION_CONFLICT, "数据已被他人修改，请刷新后重试"),
    PUBLISH_INCOMPLETE(422, OpsError.PUBLISH_INCOMPLETE, "上线前还有必填项没填"),
    STATUS_CONFLICT(409, "OPS_ACTIVITY_STATUS_CONFLICT", "活动现在的状态不能这样操作"),
    ORGANIZER_UNKNOWN(422, "OPS_ACTIVITY_ORGANIZER_UNKNOWN", "主办或承办机构不存在、已停用，或不是服务商、平台机构"),
    CITY_UNKNOWN(422, "OPS_CITY_UNKNOWN", "城市不在平台城市目录里"),
    LOCKED_BY_ENROLLMENTS(409, "OPS_ACTIVITY_HAS_ENROLLMENTS", "活动已有报名，不能改收费方式、主办机构和城市"),
    SESSION_HAS_ENROLLMENTS(409, "OPS_ACTIVITY_SESSION_HAS_ENROLLMENTS",
            "这一场已有报名：不能删除，也不能改开始、结束时间，只能停售"),
    SESSION_STARTED(409, "OPS_ACTIVITY_SESSION_STARTED", "场次已经开始，不能再改"),
    CAPACITY_BELOW_ENROLLED(409, "OPS_ACTIVITY_CAPACITY_BELOW_ENROLLED", "名额不能少于已报名人数"),
    SALE_STATUS_UNCHANGED(409, "OPS_ACTIVITY_SALE_STATUS_UNCHANGED", "场次已经是这个售卖状态"),
    SESSION_LIMIT(409, "OPS_ACTIVITY_SESSION_LIMIT", "一个活动最多 50 个场次");

    private final int status;
    private final String code;
    private final String message;

    ActivityOpsError(int status, String code, String message) {
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
