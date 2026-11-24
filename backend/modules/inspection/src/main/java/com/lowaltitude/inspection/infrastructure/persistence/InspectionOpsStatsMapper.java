package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.OffsetDateTime;

import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里巡检的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 * excluded 是不计入的账号和机构（{@link OpsStatsExclusion}）。
 */
public interface InspectionOpsStatsMapper {

    /** 上架的巡检服务。 */
    @Select("""
            SELECT COUNT(*) FROM inspection_service
            WHERE status = 'ACTIVE'
              AND provider_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long services(@Param("excluded") OpsStatsExclusion excluded);

    /** 开通城市。 */
    @Select("""
            SELECT COUNT(*) FROM platform_city WHERE service_status = 'OPEN'
            """)
    long openCities();

    /** 统计期内提交的巡检预约。 */
    @Select("""
            SELECT COUNT(*) FROM inspection_reservation
            WHERE submitted_at >= #{from}
              AND submitted_at < #{to}
              AND customer_id <> ALL (CAST(#{excluded.userArray} AS uuid[]))
              AND (customer_organization_id IS NULL
                   OR customer_organization_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[])))
              AND (provider_id IS NULL
                   OR provider_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[])))
            """)
    long reservations(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                      @Param("excluded") OpsStatsExclusion excluded);
}
