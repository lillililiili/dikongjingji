package com.lowaltitude.inspection.activity.application;

import java.time.OffsetDateTime;

import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityOpsStatsMapper;
import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import org.springframework.transaction.annotation.Transactional;

/** 运营端首页“经营概览”里活动的汇总数（口径见契约 contracts/openapi-ops-dashboard.yaml）。 */
public class ActivityOpsStatsService {

    private final ActivityOpsStatsMapper mapper;

    public ActivityOpsStatsService(ActivityOpsStatsMapper mapper) {
        this.mapper = mapper;
    }

    public record Stats(long activities, long enrollments, long checkinPeople) {
    }

    /** 区间 [from, to) 由调用方按北京时间自然日算好。 */
    @Transactional(readOnly = true)
    public Stats stats(OffsetDateTime from, OffsetDateTime to, OpsStatsExclusion excluded) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("period is invalid");
        }
        return new Stats(
                mapper.activities(excluded),
                mapper.enrollments(from, to, excluded),
                mapper.checkinPeople(from, to, excluded));
    }
}
