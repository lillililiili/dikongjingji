package com.lowaltitude.inspection.activity.infrastructure.persistence;

import java.time.OffsetDateTime;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里活动的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 */
public interface ActivityOpsStatsMapper {

    /** 已发布的活动。 */
    @Select("""
            SELECT COUNT(*) FROM activity WHERE status = 'PUBLISHED'
            """)
    long activities();

    /** 统计期内报名成功的报名单（不含待付款、已取消）。 */
    @Select("""
            SELECT COUNT(*) FROM activity_enrollment
            WHERE status = 'CONFIRMED'
              AND created_at >= #{from}
              AND created_at < #{to}
            """)
    long enrollments(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** 统计期内核销通过的人次。 */
    @Select("""
            SELECT COALESCE(SUM(people), 0)::bigint FROM activity_checkin_record
            WHERE outcome = 'CHECKED_IN'
              AND occurred_at >= #{from}
              AND occurred_at < #{to}
            """)
    long checkinPeople(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
