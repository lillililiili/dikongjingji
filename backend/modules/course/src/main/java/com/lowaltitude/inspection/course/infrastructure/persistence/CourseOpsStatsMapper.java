package com.lowaltitude.inspection.course.infrastructure.persistence;

import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里课程的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 * excluded 是不计入的账号和机构（{@link OpsStatsExclusion}）。
 */
public interface CourseOpsStatsMapper {

    /** 已上架的课程。 */
    @Select("""
            SELECT COUNT(*) FROM course
            WHERE status = 'LISTED'
              AND organization_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long courses(@Param("excluded") OpsStatsExclusion excluded);
}
