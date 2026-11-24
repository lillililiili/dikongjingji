package com.lowaltitude.inspection.proservice.application;

import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsStatsMapper;
import org.springframework.transaction.annotation.Transactional;

/** 运营端首页“经营概览”里专业服务的汇总数（口径见契约 contracts/openapi-ops-dashboard.yaml）。 */
public class ProServiceOpsStatsService {

    private final ProServiceOpsStatsMapper mapper;

    public ProServiceOpsStatsService(ProServiceOpsStatsMapper mapper) {
        this.mapper = mapper;
    }

    public record Stats(long services, long pendingProviderProfiles) {
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        return new Stats(
                mapper.services(),
                mapper.pendingProviderProfiles());
    }
}
