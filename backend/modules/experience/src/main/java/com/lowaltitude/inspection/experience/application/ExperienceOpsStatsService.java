package com.lowaltitude.inspection.experience.application;

import java.time.OffsetDateTime;

import com.lowaltitude.inspection.experience.infrastructure.persistence.ExperienceOpsStatsMapper;
import org.springframework.transaction.annotation.Transactional;

/** 运营端首页“经营概览”里场馆的汇总数（口径见契约 contracts/openapi-ops-dashboard.yaml）。 */
public class ExperienceOpsStatsService {

    private final ExperienceOpsStatsMapper mapper;

    public ExperienceOpsStatsService(ExperienceOpsStatsMapper mapper) {
        this.mapper = mapper;
    }

    public record Stats(long venues, long bookings, long checkinPeople) {
    }

    /** 区间 [from, to) 由调用方按北京时间自然日算好。 */
    @Transactional(readOnly = true)
    public Stats stats(OffsetDateTime from, OffsetDateTime to) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("period is invalid");
        }
        return new Stats(
                mapper.venues(),
                mapper.bookings(from, to),
                mapper.checkinPeople(from, to));
    }
}
