package com.lowaltitude.inspection.identity.domain;

import java.util.Optional;
import java.util.UUID;

/** 按 ID 读一个机构（G1 客服会话用来确认对方机构存在、在用、是服务商类机构）。别的模块不读身份的表。 */
public interface IdentityOrganizationPort {

    /** 不存在或已停用时为空。 */
    Optional<IdentityOrganization> findActiveOrganization(UUID organizationId);
}
