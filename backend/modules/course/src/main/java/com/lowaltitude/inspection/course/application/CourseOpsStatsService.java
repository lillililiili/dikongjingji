package com.lowaltitude.inspection.course.application;

import com.lowaltitude.inspection.course.infrastructure.persistence.CourseOpsStatsMapper;
import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import org.springframework.transaction.annotation.Transactional;

/** 运营端首页“经营概览”里课程的汇总数（口径见契约 contracts/openapi-ops-dashboard.yaml）。 */
public class CourseOpsStatsService {

    private final CourseOpsStatsMapper mapper;

    public CourseOpsStatsService(CourseOpsStatsMapper mapper) {
        this.mapper = mapper;
    }

    public record Stats(long courses) {
    }

    @Transactional(readOnly = true)
    public Stats stats(OpsStatsExclusion excluded) {
        return new Stats(
                mapper.courses(excluded));
    }
}
