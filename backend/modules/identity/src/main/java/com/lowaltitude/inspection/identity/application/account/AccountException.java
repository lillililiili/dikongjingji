package com.lowaltitude.inspection.identity.application.account;

/**
 * 账号与企业组织的业务拒绝（契约 openapi-identity-account.yaml 的 IdentityAccountErrorCode）。
 * 状态码由启动模块的 IdentityAccountExceptionHandler 按 error 映射；NOT_FOUND 是 404，INVALID 是 400。
 */
public final class AccountException extends RuntimeException {

    public enum Error {
        INVALID(400),
        /** 验证码登录时账号不可用（已停用、已注销）：和令牌无效一样回答。 */
        UNAUTHORIZED(401),
        NOT_FOUND(404),
        SMS_CODE_INVALID(400),
        PHONE_TICKET_INVALID(400),
        SMS_TOO_FREQUENT(429),
        PHONE_LOCKED(429),
        SMS_UNAVAILABLE(503),
        PHONE_REQUIRED(409),
        PHONE_TAKEN(409),
        PHONE_UNCHANGED(409),
        PASSWORD_INVALID(400),
        PASSWORD_NOT_ELIGIBLE(403),
        PASSWORD_ALREADY_SET(409),
        PASSWORD_NOT_SET(409),
        PASSWORD_CURRENT_INVALID(400),
        PASSWORD_UNCHANGED(400),
        PASSWORD_CODE_REQUIRED(409),
        PASSWORD_CREDENTIALS_INVALID(401),
        PASSWORD_TRY_LATER(429),
        SESSION_IS_CURRENT(409),
        CLOSURE_CONDITIONS_UNMET(409),
        CLOSURE_ALREADY_PENDING(409),
        CLOSURE_NOT_PENDING(409),
        EXPORT_IN_PROGRESS(409),
        EXPORT_NOT_READY(409),
        EXPORT_UNAVAILABLE(503),
        ENTERPRISE_ADMIN_REQUIRED(403),
        ENTERPRISE_CREDIT_CODE_TAKEN(409),
        ENTERPRISE_LIMIT(409),
        ENTERPRISE_MEMBER_LIMIT(409),
        ENTERPRISE_LAST_ADMIN(409),
        ENTERPRISE_ALREADY_MEMBER(409),
        INVITATION_EXPIRED(409),
        INVITATION_NOT_PENDING(409),
        IDEMPOTENCY_CONFLICT(409);

        private final int status;

        Error(int status) {
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Error error;

    public AccountException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
