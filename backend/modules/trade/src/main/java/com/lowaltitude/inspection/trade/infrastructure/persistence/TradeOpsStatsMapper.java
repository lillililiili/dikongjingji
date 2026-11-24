package com.lowaltitude.inspection.trade.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里交易的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)；按天分组用北京时间。多列的查询都给列起和记录字段同名的别名，免得同名列（count）映射错位。
 * excluded 是不计入的账号和机构（{@link OpsStatsExclusion}）；退款异常是待处理，不排除。
 */
public interface TradeOpsStatsMapper {

    /** 买家、收款机构、企业付款主体都不在不计入的名单里；付款主体是个人时为空。 */
    String COUNTED = """
              AND buyer_id <> ALL (CAST(#{excluded.userArray} AS uuid[]))
              AND merchant_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
              AND (payer_organization_id IS NULL
                   OR payer_organization_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[])))
            """;

    record PeriodRow(long paidMinor, long refundedMinor, long paidOrders, long payingCustomers) {
    }

    record DayRow(LocalDate day, long netPaidMinor, long paidOrders) {
    }

    record ShareRow(String code, long netPaidMinor, long paidOrders) {
    }

    record FunnelRow(long submitted, long paid, long kept) {
    }

    @Select("""
            SELECT COALESCE(SUM(paid_minor), 0)::bigint AS paid_minor,
                   COALESCE(SUM(refunded_minor), 0)::bigint AS refunded_minor,
                   COUNT(*) AS paid_orders, COUNT(DISTINCT buyer_id) AS paying_customers
            FROM trade_order
            WHERE paid_at >= #{from} AND paid_at < #{to}
            """ + COUNTED)
    PeriodRow period(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                     @Param("excluded") OpsStatsExclusion excluded);

    @Select("""
            SELECT COALESCE(SUM(paid_minor - refunded_minor), 0)::bigint
            FROM trade_order
            WHERE paid_at IS NOT NULL
            """ + COUNTED)
    long lifetimeNetPaid(@Param("excluded") OpsStatsExclusion excluded);

    @Select("""
            SELECT (paid_at AT TIME ZONE 'Asia/Shanghai')::date AS day,
                   SUM(paid_minor - refunded_minor)::bigint AS net_paid_minor, COUNT(*) AS paid_orders
            FROM trade_order
            WHERE paid_at >= #{from} AND paid_at < #{to}
            """ + COUNTED + """
            GROUP BY 1
            ORDER BY 1
            """)
    List<DayRow> daily(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                       @Param("excluded") OpsStatsExclusion excluded);

    @Select("""
            SELECT source AS code, SUM(paid_minor - refunded_minor)::bigint AS net_paid_minor,
                   COUNT(*) AS paid_orders
            FROM trade_order
            WHERE paid_at >= #{from} AND paid_at < #{to}
            """ + COUNTED + """
            GROUP BY source
            ORDER BY net_paid_minor DESC, code
            """)
    List<ShareRow> bySource(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                            @Param("excluded") OpsStatsExclusion excluded);

    @Select("""
            SELECT city_code AS code, SUM(paid_minor - refunded_minor)::bigint AS net_paid_minor,
                   COUNT(*) AS paid_orders
            FROM trade_order
            WHERE paid_at >= #{from} AND paid_at < #{to}
            """ + COUNTED + """
            GROUP BY city_code
            ORDER BY net_paid_minor DESC, code
            LIMIT #{limit}
            """)
    List<ShareRow> byCity(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                          @Param("limit") int limit, @Param("excluded") OpsStatsExclusion excluded);

    @Select("""
            SELECT COUNT(*) AS submitted, COUNT(paid_at) AS paid,
                   COUNT(*) FILTER (WHERE paid_at IS NOT NULL AND status <> 'REFUNDED') AS kept
            FROM trade_order
            WHERE created_at >= #{from} AND created_at < #{to}
            """ + COUNTED)
    FunnelRow funnel(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                     @Param("excluded") OpsStatsExclusion excluded);

    @Select("""
            SELECT COUNT(*) FROM trade_merchant_account
            WHERE active
              AND organization_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long collectingMerchants(@Param("excluded") OpsStatsExclusion excluded);

    @Select("SELECT COUNT(*) FROM trade_order WHERE status = 'REFUND_FAILED'")
    long refundFailedOrders();
}
