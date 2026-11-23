package com.lowaltitude.inspection.identity.application;

/** 机构入驻与资质审核的业务拒绝；状态码和错误码由全局异常处理按 error 映射（契约 OpsOrganizationErrorCode）。 */
public final class OnboardingException extends RuntimeException {

    public enum Error {
        /** 机构、附件或账号不存在（平台机构也当作不存在）。 */
        NOT_FOUND,
        /** 页面读到的版本已经不是当前版本：别人刚改过。 */
        VERSION_CONFLICT,
        /** 幂等键已用于内容不同的新建请求。 */
        IDEMPOTENCY_CONFLICT,
        /** 城市不在平台城市目录里（或不可选）。 */
        CITY_UNKNOWN,
        /** 登录名已被占用。 */
        LOGIN_NAME_TAKEN,
        /** 这家机构的类型不能授这个角色。 */
        ROLE_NOT_GRANTABLE,
        /** 资质资料还没提交，或不是待审核状态，不能审核。 */
        QUALIFICATION_NOT_PENDING,
        /** 审核通过前至少要有一份资质附件（营业执照等）。 */
        QUALIFICATION_INCOMPLETE,
        /** 附件数已到上限。 */
        ATTACHMENT_LIMIT,
        /** 附件存储没有配置（开发环境之外还没接对象存储）。 */
        FILE_STORE_UNAVAILABLE,
        /** 开通服务商资料前资质要先审核通过（机构有资质审核通过时间）。 */
        QUALIFICATION_NOT_APPROVED,
        /** 企业客户、已停用的机构不能开通服务商资料。 */
        PROVIDER_PROFILE_NOT_APPLICABLE
    }

    private final Error error;

    public OnboardingException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
