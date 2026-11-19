package com.lowaltitude.inspection.support.application;

import java.util.UUID;

/**
 * 回复会话的一方：平台客服（看所有对方是平台客服的会话），或一家机构的操作员（看对方是这家机构的商家、服务商会话）。
 * 二者只能是其一。
 */
public record SupportDesk(boolean platform, UUID organizationId) {

    public SupportDesk {
        if (platform == (organizationId != null)) {
            throw new IllegalArgumentException("a desk is the platform or one organization");
        }
    }

    public static SupportDesk platformSupport() {
        return new SupportDesk(true, null);
    }

    public static SupportDesk organization(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organization is required");
        }
        return new SupportDesk(false, organizationId);
    }
}
