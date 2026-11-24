package com.lowaltitude.inspection.trade.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOpsStatsMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端首页“经营概览”里交易的汇总数（契约 contracts/openapi-ops-dashboard.yaml 的口径）：按付款时间统计成交，
 * 净成交额 = 实付 − 已退。区间都是 [from, to)，由调用方按北京时间自然日算好。
 */
public class OpsTradeStatsService {

    public static final int TOP_CITIES = 8;

    private final TradeOpsStatsMapper mapper;

    public OpsTradeStatsService(TradeOpsStatsMapper mapper) {
        this.mapper = mapper;
    }

    public record Period(long netPaidMinor, long paidMinor, long refundedMinor, long paidOrders,
                         long payingCustomers) {
    }

    public record Day(LocalDate date, long netPaidMinor, long paidOrders) {
    }

    public record Share(String code, long netPaidMinor, long paidOrders) {
    }

    public record Funnel(long submitted, long paid, long kept) {
    }

    public record Stats(Period current, Period previous, long lifetimeNetPaidMinor, List<Day> daily,
                        List<Share> bySource, List<Share> byCity, Funnel funnel, long collectingMerchants,
                        long refundFailedOrders) {
    }

    @Transactional(readOnly = true)
    public Stats stats(OffsetDateTime from, OffsetDateTime to, OffsetDateTime previousFrom,
                       OpsStatsExclusion excluded) {
        if (!previousFrom.isBefore(from) || !from.isBefore(to)) {
            throw new IllegalArgumentException("period is invalid");
        }
        TradeOpsStatsMapper.FunnelRow funnel = mapper.funnel(from, to, excluded);
        return new Stats(
                period(mapper.period(from, to, excluded)),
                period(mapper.period(previousFrom, from, excluded)),
                mapper.lifetimeNetPaid(excluded),
                mapper.daily(from, to, excluded).stream()
                        .map(row -> new Day(row.day(), row.netPaidMinor(), row.paidOrders())).toList(),
                shares(mapper.bySource(from, to, excluded)),
                shares(mapper.byCity(from, to, TOP_CITIES, excluded)),
                new Funnel(funnel.submitted(), funnel.paid(), funnel.kept()),
                mapper.collectingMerchants(excluded),
                mapper.refundFailedOrders());
    }

    private static Period period(TradeOpsStatsMapper.PeriodRow row) {
        return new Period(row.paidMinor() - row.refundedMinor(), row.paidMinor(), row.refundedMinor(),
                row.paidOrders(), row.payingCustomers());
    }

    private static List<Share> shares(List<TradeOpsStatsMapper.ShareRow> rows) {
        return rows.stream().map(row -> new Share(row.code(), row.netPaidMinor(), row.paidOrders())).toList();
    }
}
