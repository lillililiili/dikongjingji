package com.lowaltitude.inspection.experience.application;

/** 体验记录的业务拒绝（S5-4）；对外的状态码和错误码由全局异常处理按 error 映射（契约 ExperienceErrorCode）。 */
public final class ExperienceRecordException extends RuntimeException {

    public enum Error {
        NOT_FOUND,
        FORBIDDEN,
        /** 同一个幂等键已经用于内容不同的成绩。 */
        IDEMPOTENCY_CONFLICT,
        /** 项目没发布，或者不是这条路由能记的类型（成绩只给配了模板的仿真，试用只给配了时长的试用和试听）。 */
        NOT_RECORDABLE,
        /** 成绩的数字不合规则：关卡数和模板不符、用时和起止时间对不上、时间不合理。 */
        RESULT_INVALID,
        /** 只有试用和试听的记录能“结束”。 */
        NOT_TRIAL
    }

    private final Error error;

    public ExperienceRecordException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
