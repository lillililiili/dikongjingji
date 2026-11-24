package com.lowaltitude.inspection.support.application;

import com.lowaltitude.inspection.support.infrastructure.persistence.SupportOpsStatsMapper;
import org.springframework.transaction.annotation.Transactional;

/** 运营端首页“经营概览”里客服的汇总数（口径见契约 contracts/openapi-ops-dashboard.yaml）。 */
public class SupportOpsStatsService {

    private final SupportOpsStatsMapper mapper;

    public SupportOpsStatsService(SupportOpsStatsMapper mapper) {
        this.mapper = mapper;
    }

    public record Stats(long pendingFeedback, long conversationsAwaitingReply) {
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        return new Stats(
                mapper.pendingFeedback(),
                mapper.conversationsAwaitingReply());
    }
}
