package com.lowaltitude.inspection.proservice.infrastructure.persistence;

import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里专业服务的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 */
public interface ProServiceOpsStatsMapper {

    /** 上架的专业服务，不含示例。 */
    @Select("""
            SELECT COUNT(*) FROM pro_service WHERE status = 'PUBLISHED' AND NOT sample
            """)
    long services();

    /** 待审的服务商资料。 */
    @Select("""
            SELECT COUNT(*) FROM pro_provider_profile WHERE status = 'PENDING'
            """)
    long pendingProviderProfiles();
}
