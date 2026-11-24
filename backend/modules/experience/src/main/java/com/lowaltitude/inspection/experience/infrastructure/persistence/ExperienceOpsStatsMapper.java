package com.lowaltitude.inspection.experience.infrastructure.persistence;

import java.time.OffsetDateTime;

import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里场馆的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 * excluded 是不计入的账号和机构（{@link OpsStatsExclusion}）。
 */
public interface ExperienceOpsStatsMapper {

    /** 已发布的场馆。 */
    @Select("""
            SELECT COUNT(*) FROM venue
            WHERE status = 'PUBLISHED'
              AND operator_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long venues(@Param("excluded") OpsStatsExclusion excluded);

    /** 统计期内付款确认的到馆预约。 */
    @Select("""
            SELECT COUNT(*) FROM venue_booking b
            JOIN venue v ON v.id = b.venue_id
            WHERE b.confirmed_at >= #{from}
              AND b.confirmed_at < #{to}
              AND b.account_id <> ALL (CAST(#{excluded.userArray} AS uuid[]))
              AND v.operator_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long bookings(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                  @Param("excluded") OpsStatsExclusion excluded);

    /** 统计期内核销通过的人次。 */
    @Select("""
            SELECT COALESCE(SUM(c.people), 0)::bigint FROM venue_checkin_record c
            JOIN venue_booking b ON b.id = c.booking_id
            JOIN venue v ON v.id = b.venue_id
            WHERE c.outcome = 'CHECKED_IN'
              AND c.occurred_at >= #{from}
              AND c.occurred_at < #{to}
              AND b.account_id <> ALL (CAST(#{excluded.userArray} AS uuid[]))
              AND v.operator_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
              AND c.organization_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long checkinPeople(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                       @Param("excluded") OpsStatsExclusion excluded);
}
