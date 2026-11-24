package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.OffsetDateTime;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里巡检的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 */
public interface InspectionOpsStatsMapper {

    /** 上架的巡检服务。 */
    @Select("""
            SELECT COUNT(*) FROM inspection_service WHERE status = 'ACTIVE'
            """)
    long services();

    /** 开通城市。 */
    @Select("""
            SELECT COUNT(*) FROM platform_city WHERE service_status = 'OPEN'
            """)
    long openCities();

    /** 统计期内提交的巡检预约。 */
    @Select("""
            SELECT COUNT(*) FROM inspection_reservation WHERE submitted_at >= #{from} AND submitted_at < #{to}
            """)
    long reservations(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
