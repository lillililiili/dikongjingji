package com.lowaltitude.inspection.course.infrastructure.persistence;

import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里课程的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 */
public interface CourseOpsStatsMapper {

    /** 已上架的课程。 */
    @Select("""
            SELECT COUNT(*) FROM course WHERE status = 'LISTED'
            """)
    long courses();
}
