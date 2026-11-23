package com.lowaltitude.inspection.identity.domain;

/** Matches the identity_user.status values defined by the identity migration. */
public enum IdentityAccountStatus {
    ACTIVE,
    LOCKED,
    DISABLED,
    /** 注销完成、已匿名化（V010）：不能再登录。 */
    CLOSED
}
