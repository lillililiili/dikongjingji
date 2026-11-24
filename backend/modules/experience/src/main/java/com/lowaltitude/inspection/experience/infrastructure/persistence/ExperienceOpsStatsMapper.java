package com.lowaltitude.inspection.experience.infrastructure.persistence;

import java.time.OffsetDateTime;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里场馆的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 */
public interface ExperienceOpsStatsMapper {

    /** 已发布的场馆。 */
    @Select("""
            SELECT COUNT(*) FROM venue WHERE status = 'PUBLISHED'
            """)
    long venues();

    /** 统计期内付款确认的到馆预约。 */
    @Select("""
            SELECT COUNT(*) FROM venue_booking WHERE confirmed_at >= #{from} AND confirmed_at < #{to}
            """)
    long bookings(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    /** 统计期内核销通过的人次。 */
    @Select("""
            SELECT COALESCE(SUM(people), 0)::bigint FROM venue_checkin_record
            WHERE outcome = 'CHECKED_IN'
              AND occurred_at >= #{from}
              AND occurred_at < #{to}
            """)
    long checkinPeople(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
