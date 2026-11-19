package com.lowaltitude.inspection.trade.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 对账（E06）的 SQL 文本（没有可用的数据库测试容器，做法同 TradeRefundMapperContractTest）：只用绑定参数；
 * 一天按 [from, to) 取单据；要对账的子商户的范围；平台这边“当天的”支付和退款的条件；结果按天、子商户覆盖；
 * 差异先删后插。
 */
class TradeReconciliationMapperContractTest {

    @Test
    void everyMethodCarriesOneStatementThatOnlyBindsParameters() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(TradeReconciliationMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(12);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        // 只有按单号批量取和按结果 ID 取差异用 <script> 的 foreach，别的都是普通语句。
        assertThat(statements.entrySet().stream()
                .filter(entry -> entry.getValue().contains("<script>"))
                .map(Map.Entry::getKey)
                .sorted()
                .toList()).containsExactly("findDifferences", "findPaymentsByOutTradeNos", "findRefundsByOutRefundNos");
        // 对账不改订单、支付单、退款单：写的只有两张对账表。
        assertThat(statements.values()).allSatisfy(sql -> assertThat(normalized(sql))
                .doesNotContain("UPDATE trade_order")
                .doesNotContain("UPDATE trade_payment")
                .doesNotContain("UPDATE trade_refund")
                .doesNotContain("INSERT INTO trade_order")
                .doesNotContain("INSERT INTO trade_payment")
                .doesNotContain("INSERT INTO trade_refund ")
                .doesNotContain("FOR UPDATE"));
    }

    @Test
    void theColumnListsMatchTheRows() {
        assertThat(columns(TradeReconciliationMapper.PAYMENT_COLUMNS, "p."))
                .containsExactlyElementsOf(snakeCase(TradeReconciliationMapper.BillPaymentRow.class));
        assertThat(columns(TradeReconciliationMapper.REFUND_COLUMNS, "r.", "p."))
                .containsExactlyElementsOf(snakeCase(TradeReconciliationMapper.BillRefundRow.class));
        assertThat(columns(sql("findReconciliation"), ""))
                .containsExactlyElementsOf(snakeCase(TradeReconciliationMapper.ReconciliationRow.class));
        assertThat(columns(sql("findReconciliations"), ""))
                .containsExactlyElementsOf(snakeCase(TradeReconciliationMapper.ReconciliationRow.class));
        assertThat(columns(sql("findDifferences").replace("<script>", ""), ""))
                .containsExactlyElementsOf(snakeCase(TradeReconciliationMapper.DifferenceRow.class));
        assertThat(normalized(TradeReconciliationMapper.PAYMENT_COLUMNS)).endsWith("FROM trade_payment p");
        // 退款的子商户号和商户订单号取自原支付单（退款单上没有）。
        assertThat(normalized(TradeReconciliationMapper.REFUND_COLUMNS))
                .isEqualTo("SELECT r.out_refund_no, p.out_trade_no, p.sub_mchid, r.amount_minor, r.status"
                        + " FROM trade_refund r JOIN trade_payment p ON p.id = r.payment_id");
        for (String method : List.of("findPaymentsSucceededBetween")) {
            assertThat(sql(method)).as(method).startsWith(TradeReconciliationMapper.PAYMENT_COLUMNS);
        }
        assertThat(sql("findPaymentsByOutTradeNos")).startsWith("<script>" + TradeReconciliationMapper.PAYMENT_COLUMNS);
        assertThat(sql("findRefundsAcceptedBetween")).startsWith(TradeReconciliationMapper.REFUND_COLUMNS);
        assertThat(sql("findRefundsByOutRefundNos")).startsWith("<script>" + TradeReconciliationMapper.REFUND_COLUMNS);
    }

    // 同一天、同一个子商户排队：事务级的咨询锁（事务结束自动放），键里带日期和子商户号。
    @Test
    void theRunIsSerialisedPerDayAndMerchant() {
        assertThat(normalized(sql("lock")))
                .isEqualTo("SELECT 1 FROM ( SELECT pg_advisory_xact_lock(hashtextextended("
                        + " 'trade-reconciliation:' || CAST(#{billDate} AS text) || ':' || #{subMchid}, 0)) ) locked");
        assertThat(sql("lock")).doesNotContain("pg_advisory_lock(");
    }

    // 要对账的子商户：这一天结束前登记、现在启用的收款账户，加上那天有付款成功的、有退款受理的（账户后来停用了的也要对）。
    @Test
    void theMerchantsToReconcileAreTheRegisteredOnesAndThoseWithMoneyThatDay() {
        assertThat(normalized(sql("findSubMchidsToReconcile")))
                .isEqualTo("SELECT sub_mchid FROM trade_merchant_account WHERE active AND created_at < #{to}"
                        + " UNION SELECT p.sub_mchid FROM trade_payment p"
                        + " WHERE p.succeeded_at >= #{from} AND p.succeeded_at < #{to}"
                        + " UNION SELECT p.sub_mchid FROM trade_refund r JOIN trade_payment p ON p.id = r.payment_id"
                        + " WHERE r.accepted_at >= #{from} AND r.accepted_at < #{to}"
                        + " ORDER BY 1");
        // UNION（不是 UNION ALL）去重。
        assertThat(sql("findSubMchidsToReconcile")).doesNotContain("UNION ALL");
    }

    // 平台这边当天的单据：付款成功（状态 SUCCEEDED）且付款时间在这一天的支付单；受理时间在这一天的退款（不论后来的状态）。
    @Test
    void thePlatformSideOfADayIsHalfOpenAndPerMerchant() {
        assertThat(where("findPaymentsSucceededBetween"))
                .isEqualTo("WHERE p.sub_mchid = #{subMchid} AND p.status = 'SUCCEEDED'"
                        + " AND p.succeeded_at >= #{from} AND p.succeeded_at < #{to} ORDER BY p.out_trade_no");
        assertThat(where("findRefundsAcceptedBetween"))
                .isEqualTo("WHERE p.sub_mchid = #{subMchid}"
                        + " AND r.accepted_at >= #{from} AND r.accepted_at < #{to} ORDER BY r.out_refund_no");
        assertThat(where("findRefundsAcceptedBetween")).doesNotContain("status");
    }

    // 账单里的单号按单号取平台的单据，不限子商户（子商户不同由服务判为“微信有平台没有”）。
    @Test
    void theBillsNumbersAreLookedUpWithAForeach() {
        assertThat(where("findPaymentsByOutTradeNos"))
                .isEqualTo("WHERE p.out_trade_no IN <foreach collection=\"outTradeNos\" item=\"no\" open=\"(\""
                        + " separator=\",\" close=\")\">#{no}</foreach> </script>");
        assertThat(where("findRefundsByOutRefundNos"))
                .isEqualTo("WHERE r.out_refund_no IN <foreach collection=\"outRefundNos\" item=\"no\" open=\"(\""
                        + " separator=\",\" close=\")\">#{no}</foreach> </script>");
        assertThat(where("findDifferences"))
                .isEqualTo("WHERE reconciliation_id IN <foreach collection=\"reconciliationIds\" item=\"id\" open=\"(\""
                        + " separator=\",\" close=\")\">#{id}</foreach> ORDER BY reconciliation_id, id </script>");
    }

    @Test
    void theResultsAreReadByDayAndMerchant() {
        assertThat(where("findReconciliation"))
                .isEqualTo("WHERE bill_date = #{billDate} AND sub_mchid = #{subMchid}");
        assertThat(where("findReconciliations")).isEqualTo("WHERE bill_date = #{billDate} ORDER BY sub_mchid");
        assertThat(normalized(sql("findReconciliations"))).contains("FROM trade_reconciliation WHERE");
    }

    // 一天一个子商户一条：重跑覆盖状态、匹配数、失败原因和导入时间，ID 不变；返回 ID 给差异用。
    @Test
    void aRunOverwritesTheResultOfItsDayAndMerchant() throws Exception {
        assertThat(normalized(sql("saveReconciliation")))
                .isEqualTo("INSERT INTO trade_reconciliation (bill_date, sub_mchid, status, matched_count, failure_reason,"
                        + " imported_at) VALUES (#{billDate}, #{subMchid}, #{status}, #{matchedCount}, #{failureReason},"
                        + " #{at}) ON CONFLICT (bill_date, sub_mchid) DO UPDATE SET status = EXCLUDED.status,"
                        + " matched_count = EXCLUDED.matched_count, failure_reason = EXCLUDED.failure_reason,"
                        + " imported_at = EXCLUDED.imported_at RETURNING id");
        Method method = method("saveReconciliation");
        assertThat(method.getAnnotation(Select.class)).isNotNull();
        assertThat(method.getAnnotation(Options.class)).isNotNull();
        assertThat(method.getAnnotation(Options.class).flushCache()).isEqualTo(Options.FlushCachePolicy.TRUE);
        assertThat(method.getReturnType()).isEqualTo(java.util.UUID.class);
    }

    // 差异：按结果删光再插；插入不带 ID（表默认生成）。
    @Test
    void theDifferencesAreReplacedForAResult() {
        assertThat(normalized(sql("deleteDifferences")))
                .isEqualTo("DELETE FROM trade_reconciliation_difference WHERE reconciliation_id = #{reconciliationId}");
        assertThat(normalized(sql("insertDifference")))
                .isEqualTo("INSERT INTO trade_reconciliation_difference (reconciliation_id, kind, out_trade_no,"
                        + " out_refund_no, platform_amount_minor, channel_amount_minor, created_at)"
                        + " VALUES (#{reconciliationId}, #{kind}, #{outTradeNo}, #{outRefundNo}, #{platformAmountMinor},"
                        + " #{channelAmountMinor}, #{at})");
        assertThat(method("deleteDifferences").getAnnotation(Update.class)).isNotNull();
        assertThat(method("insertDifference").getAnnotation(Update.class)).isNotNull();
    }

    // ---- 辅助 ----

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : TradeReconciliationMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            Insert insert = method.getAnnotation(Insert.class);
            Update update = method.getAnnotation(Update.class);
            String[] text = select != null ? select.value()
                    : insert != null ? insert.value()
                    : update != null ? update.value()
                    : null;
            if (text != null) {
                statements.put(method.getName(), String.join("\n", text).strip());
            }
        }
        return statements;
    }

    private static Method method(String name) {
        return Arrays.stream(TradeReconciliationMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static String sql(String methodName) {
        String sql = statements().get(methodName);
        assertThat(sql).as(methodName).isNotNull();
        return sql;
    }

    /** WHERE 起到结尾。 */
    private static String where(String methodName) {
        String text = normalized(sql(methodName));
        return text.substring(text.indexOf("WHERE"));
    }

    private static List<String> columns(String select, String... aliases) {
        String text = normalized(select);
        String list = text.substring(text.indexOf("SELECT") + "SELECT".length(), text.indexOf(" FROM "));
        return Arrays.stream(list.split(","))
                .map(String::trim)
                .map(column -> {
                    String result = column;
                    for (String alias : aliases) {
                        if (!alias.isEmpty()) {
                            result = result.replace(alias, "");
                        }
                    }
                    return result;
                })
                .toList();
    }

    private static List<String> snakeCase(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName().replaceAll("([A-Z])", "_$1").toLowerCase())
                .toList();
    }

    /** 去掉注释、把连续空白并成一个空格。 */
    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }
}
