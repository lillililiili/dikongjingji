package com.lowaltitude.inspection.identity.domain;

import java.util.UUID;

/**
 * 账号的当前身份（S5-7）：个人，或本人所在的一家企业。企业时带企业名称和本人在企业里的角色（ADMIN / MEMBER）。
 */
public record CurrentIdentity(String type, UUID enterpriseId, String enterpriseName, String role) {

    public static final String PERSONAL = "PERSONAL";
    public static final String ENTERPRISE = "ENTERPRISE";

    public static CurrentIdentity personal() {
        return new CurrentIdentity(PERSONAL, null, null, null);
    }

    public boolean isEnterprise() {
        return ENTERPRISE.equals(type);
    }
}
