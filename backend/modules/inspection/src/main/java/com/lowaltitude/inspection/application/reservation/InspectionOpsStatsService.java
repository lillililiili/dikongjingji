package com.lowaltitude.inspection.application.reservation;

import java.time.OffsetDateTime;

import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionOpsStatsMapper;
import org.springframework.transaction.annotation.Transactional;

/** 运营端首页“经营概览”里巡检的汇总数（口径见契约 contracts/openapi-ops-dashboard.yaml）。 */
public class InspectionOpsStatsService {

    private final InspectionOpsStatsMapper mapper;

    public InspectionOpsStatsService(InspectionOpsStatsMapper mapper) {
        this.mapper = mapper;
    }

    public record Stats(long services, long openCities, long reservations) {
    }

    /** 区间 [from, to) 由调用方按北京时间自然日算好。 */
    @Transactional(readOnly = true)
    public Stats stats(OffsetDateTime from, OffsetDateTime to, OpsStatsExclusion excluded) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("period is invalid");
        }
        return new Stats(
                mapper.services(excluded),
                mapper.openCities(),
                mapper.reservations(from, to, excluded));
    }
}
