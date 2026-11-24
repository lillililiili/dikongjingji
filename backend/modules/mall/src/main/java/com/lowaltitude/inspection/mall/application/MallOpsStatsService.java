package com.lowaltitude.inspection.mall.application;

import com.lowaltitude.inspection.mall.infrastructure.persistence.MallOpsStatsMapper;
import org.springframework.transaction.annotation.Transactional;

/** 运营端首页“经营概览”里商城的汇总数（口径见契约 contracts/openapi-ops-dashboard.yaml）。 */
public class MallOpsStatsService {

    private final MallOpsStatsMapper mapper;

    public MallOpsStatsService(MallOpsStatsMapper mapper) {
        this.mapper = mapper;
    }

    public record Stats(long products, long sellingMerchants, long pendingMerchantProfiles) {
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        return new Stats(
                mapper.products(),
                mapper.sellingMerchants(),
                mapper.pendingMerchantProfiles());
    }
}
