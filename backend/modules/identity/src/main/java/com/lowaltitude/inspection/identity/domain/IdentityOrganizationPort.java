package com.lowaltitude.inspection.identity.domain;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 按 ID 读机构（G1 客服会话用来确认对方机构存在、在用、是服务商类机构；R6-4 报价、租赁等页面问资质是否审核过）。
 * 别的模块不读身份的表。
 */
public interface IdentityOrganizationPort {

    /** 不存在或已停用时为空。 */
    Optional<IdentityOrganization> findActiveOrganization(UUID organizationId);

    /**
     * 这些机构里在用、并且平台已审核过资质（有审核通过时间）的那些。不认识的、停用的、没审核的都不在结果里；
     * 空集合或 null 返回空集。
     */
    Set<UUID> qualificationReviewed(Collection<UUID> organizationIds);
}
