package com.lowaltitude.inspection.identity.application.account;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import com.lowaltitude.inspection.identity.infrastructure.persistence.IdentityOpsStatsMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端首页“经营概览”里客户和机构的汇总数（契约 contracts/openapi-ops-dashboard.yaml 的口径）。
 * 区间都是 [from, to)，由调用方按北京时间自然日算好。
 */
public class OpsIdentityStatsService {

    private final IdentityOpsStatsMapper mapper;

    public OpsIdentityStatsService(IdentityOpsStatsMapper mapper) {
        this.mapper = mapper;
    }

    public record DayCount(LocalDate date, long count) {
    }

    public record Stats(long customers, long newCustomers, long newCustomersPrevious, long activeCustomers,
                        long enterprises, List<DayCount> dailyNewCustomers, long providers,
                        long qualificationReviewedProviders, long newProviders, long pendingQualifications) {
    }

    @Transactional(readOnly = true)
    public Stats stats(OffsetDateTime from, OffsetDateTime to, OffsetDateTime previousFrom,
                       OpsStatsExclusion excluded) {
        if (!previousFrom.isBefore(from) || !from.isBefore(to)) {
            throw new IllegalArgumentException("period is invalid");
        }
        return new Stats(
                mapper.customers(excluded),
                mapper.newCustomers(from, to, excluded),
                mapper.newCustomers(previousFrom, from, excluded),
                mapper.activeCustomers(from, to, excluded),
                mapper.enterprises(excluded),
                mapper.dailyNewCustomers(from, to, excluded).stream()
                        .map(row -> new DayCount(row.day(), row.count())).toList(),
                mapper.providers(excluded),
                mapper.qualificationReviewedProviders(excluded),
                mapper.newProviders(from, to, excluded),
                mapper.pendingQualifications());
    }
}
