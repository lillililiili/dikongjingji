package com.lowaltitude.inspection.activity.infrastructure.persistence;

import java.time.OffsetDateTime;

import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里活动的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 * excluded 是不计入的账号和机构（{@link OpsStatsExclusion}）。
 */
public interface ActivityOpsStatsMapper {

    /** 已发布的活动。 */
    @Select("""
            SELECT COUNT(*) FROM activity
            WHERE status = 'PUBLISHED'
              AND organizer_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long activities(@Param("excluded") OpsStatsExclusion excluded);

    /** 统计期内报名成功的报名单（不含待付款、已取消）。 */
    @Select("""
            SELECT COUNT(*) FROM activity_enrollment e
            JOIN activity a ON a.id = e.activity_id
            WHERE e.status = 'CONFIRMED'
              AND e.created_at >= #{from}
              AND e.created_at < #{to}
              AND e.account_id <> ALL (CAST(#{excluded.userArray} AS uuid[]))
              AND a.organizer_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long enrollments(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                     @Param("excluded") OpsStatsExclusion excluded);

    /** 统计期内核销通过的人次。 */
    @Select("""
            SELECT COALESCE(SUM(c.people), 0)::bigint FROM activity_checkin_record c
            JOIN activity_enrollment_ticket t ON t.id = c.ticket_id
            JOIN activity_enrollment e ON e.id = t.enrollment_id
            JOIN activity a ON a.id = e.activity_id
            WHERE c.outcome = 'CHECKED_IN'
              AND c.occurred_at >= #{from}
              AND c.occurred_at < #{to}
              AND e.account_id <> ALL (CAST(#{excluded.userArray} AS uuid[]))
              AND a.organizer_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
              AND c.organization_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long checkinPeople(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                       @Param("excluded") OpsStatsExclusion excluded);
}
