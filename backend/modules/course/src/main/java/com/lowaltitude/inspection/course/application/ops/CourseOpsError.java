package com.lowaltitude.inspection.course.application.ops;

import com.lowaltitude.inspection.ops.common.OpsError;

/** 运营端“课程”的业务拒绝（契约 openapi-ops-course.yaml 的 OpsCourseErrorCode）。 */
public enum CourseOpsError implements OpsError {
    NOT_FOUND(404, OpsError.NOT_FOUND, "资源不存在"),
    VERSION_CONFLICT(409, OpsError.VERSION_CONFLICT, "数据已被他人修改，请刷新后重试"),
    PUBLISH_INCOMPLETE(422, OpsError.PUBLISH_INCOMPLETE, "上架前还有必填项没填"),
    STATUS_CONFLICT(409, "OPS_COURSE_STATUS_CONFLICT", "课程现在的状态不能这样操作"),
    ORGANIZATION_UNKNOWN(422, "OPS_COURSE_ORGANIZATION_UNKNOWN", "开课机构不存在、已停用，或不是服务商、平台机构"),
    EXPERIENCE_TAKEN(409, "OPS_COURSE_EXPERIENCE_TAKEN", "这个试听入口已经给了别的课程"),
    LESSON_IN_USE(409, "OPS_COURSE_LESSON_IN_USE", "要删的课时已经有人学过，只能改标题、时长和视频");

    private final int status;
    private final String code;
    private final String message;

    CourseOpsError(int status, String code, String message) {
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
