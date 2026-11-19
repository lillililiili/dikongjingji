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
 * 退款单的 SQL 文本（E05；没有可用的数据库测试容器，做法同 TradePaymentMapperContractTest）：只用绑定参数；
 * 买家入口带 buyer_id、商家入口带 merchant_id；锁语句一条只锁一张表的一行；各个状态迁移只改处在允许状态的行；
 * “在路上的退款”和定时兜底的查询条件。
 */
class TradeRefundMapperContractTest {

    private static final String OPEN = "('REQUESTED', 'PROCESSING')";
    private static final String UNFINISHED = "('REQUESTED', 'PROCESSING', 'ABNORMAL')";

    @Test
    void everyMethodCarriesOneStatementThatOnlyBindsParameters() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(TradeRefundMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(21);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql)
                .doesNotContain("${")
                .doesNotContain("<script>"));
    }

    @Test
    void theColumnListsMatchTheRows() {
        assertThat(columns(TradeRefundMapper.REFUND_ORDER_COLUMNS, "o."))
                .containsExactlyElementsOf(snakeCase(TradeRefundMapper.RefundOrderRow.class));
        assertThat(columns(TradeRefundMapper.REFUND_COLUMNS, "r."))
                .containsExactlyElementsOf(snakeCase(TradeRefundMapper.RefundRow.class));
        assertThat(columns(sql("findSucceededPayment"), ""))
                .containsExactlyElementsOf(snakeCase(TradeRefundMapper.PaidPaymentRow.class));
        assertThat(normalized(TradeRefundMapper.REFUND_ORDER_COLUMNS)).endsWith("FROM trade_order o");
        assertThat(normalized(TradeRefundMapper.REFUND_COLUMNS)).endsWith("FROM trade_refund r");
        for (String method : List.of("lockBuyerOrder", "lockMerchantOrder", "lockOrder")) {
            assertThat(sql(method)).as(method).startsWith(TradeRefundMapper.REFUND_ORDER_COLUMNS);
        }
        for (String method : List.of("findRefundByKey", "lockRefund", "findRefundByOutRefundNo", "findRefundsToSubmit",
                "findRefundsToQuery")) {
            assertThat(sql(method)).as(method).startsWith(TradeRefundMapper.REFUND_COLUMNS);
        }
    }

    // 买家取消只锁自己的订单，商家退款只锁本机构的商城订单（E09：业务来源的订单由业务单退改）；
    // 按 ID 锁只给回调、查询、定时任务和业务来源的退款用。
    @Test
    void theBuyerAndMerchantEntriesAreLimitedToTheirOwnOrders() {
        assertThat(where("lockBuyerOrder")).isEqualTo("WHERE o.id = #{orderId} AND o.buyer_id = #{buyerId} FOR UPDATE");
        assertThat(where("lockMerchantOrder"))
                .isEqualTo("WHERE o.id = #{orderId} AND o.merchant_id = #{merchantId} AND o.source = 'MALL' FOR UPDATE");
        assertThat(where("lockOrder")).isEqualTo("WHERE o.id = #{orderId} FOR UPDATE");
    }

    // 锁的顺序在服务里固定为先订单后退款单；SQL 这一侧每条锁语句只锁一张表，不连带锁另一张。
    @Test
    void everyLockingStatementLocksRowsOfOneTableOnly() {
        assertThat(where("lockRefund")).isEqualTo("WHERE r.id = #{refundId} FOR UPDATE");
        for (String method : List.of("lockBuyerOrder", "lockMerchantOrder", "lockOrder", "lockRefund")) {
            assertThat(normalized(sql(method))).as(method).doesNotContain("JOIN").endsWith("FOR UPDATE");
        }
        for (String method : List.of("findSucceededPayment", "sumOpenRefunds", "sumReservedRefunds", "countRefunds", "findRefundByKey",
                "findRefundByOutRefundNo", "countOutRefundNo", "findRefundsToSubmit", "findRefundsToQuery")) {
            assertThat(sql(method)).as(method).doesNotContain("FOR UPDATE");
        }
        assertThat(where("findRefundByKey"))
                .isEqualTo("WHERE r.order_id = #{orderId} AND r.idempotency_key = #{idempotencyKey}");
        assertThat(where("findRefundByOutRefundNo")).isEqualTo("WHERE r.out_refund_no = #{outRefundNo}");
        assertThat(normalized(sql("countOutRefundNo")))
                .isEqualTo("SELECT count(*) FROM trade_refund WHERE out_refund_no = #{outRefundNo}");
        assertThat(normalized(sql("findSucceededPayment")))
                .isEqualTo("SELECT id, out_trade_no, sub_mchid, amount_minor FROM trade_payment"
                        + " WHERE order_id = #{orderId} AND status = 'SUCCEEDED'");
    }

    // 在路上的（已建、处理中）决定订单退款有没有结束；还能退多少 = 实付 − 已退 − 还占着钱的（在路上的加异常的，
    // 异常的人工处理后仍可能退成功）；50 次的上限把关闭了的也算进去。
    @Test
    void theRefundableAmountAndTheLimitAreCountedFromTheRefunds() {
        assertThat(normalized(sql("sumOpenRefunds")))
                .isEqualTo("SELECT COALESCE(sum(amount_minor), 0) FROM trade_refund"
                        + " WHERE order_id = #{orderId} AND status IN " + OPEN);
        assertThat(normalized(sql("sumReservedRefunds")))
                .isEqualTo("SELECT COALESCE(sum(amount_minor), 0) FROM trade_refund"
                        + " WHERE order_id = #{orderId} AND status IN ('REQUESTED', 'PROCESSING', 'ABNORMAL')");
        assertThat(normalized(sql("countRefunds"))).isEqualTo("SELECT count(*) FROM trade_refund WHERE order_id = #{orderId}");
    }

    @Test
    void aNewRefundStartsWithTheTableDefaultsAndReturnsItsId() throws Exception {
        String insert = normalized(sql("insertRefund"));

        assertThat(insert)
                .startsWith("INSERT INTO trade_refund (order_id, payment_id, out_refund_no, amount_minor, reason,"
                        + " initiator, requested_by, idempotency_key, created_at, updated_at)")
                .contains("VALUES (#{orderId}, #{paymentId}, #{outRefundNo}, #{amountMinor}, #{reason}, #{initiator},"
                        + " #{requestedBy}, #{idempotencyKey}, #{at}, #{at})")
                .endsWith("RETURNING id");
        // 状态、渠道退款单号、到账时间、版本都用表的默认值：新的退款单一定是 REQUESTED。
        assertThat(insert).doesNotContain("status").doesNotContain("refund_id").doesNotContain("succeeded_at")
                .doesNotContain("version").doesNotContain("ON CONFLICT");
        // 用 @Select 跑的 INSERT … RETURNING：MyBatis 不会自己清会话缓存，要显式 flushCache。
        Method method = method("insertRefund");
        assertThat(method.getAnnotation(Select.class)).isNotNull();
        assertThat(method.getAnnotation(Options.class)).isNotNull();
        assertThat(method.getAnnotation(Options.class).flushCache()).isEqualTo(Options.FlushCachePolicy.TRUE);
        assertThat(statements().entrySet().stream()
                .filter(entry -> normalized(entry.getValue()).startsWith("INSERT"))
                .map(Map.Entry::getKey)
                .toList()).containsExactly("insertRefund");
    }

    @Test
    void theOrderChangesOnlyTouchOrdersInTheStatusesTheyLeave() {
        // 全额退款开始：已付款、已发货、已完成、上次退款失败的才进入退款中。
        assertThat(normalized(sql("markOrderRefunding")))
                .isEqualTo("UPDATE trade_order SET status = 'REFUNDING', version = version + 1, updated_at = #{at}"
                        + " WHERE id = #{orderId} AND status IN ('PAID', 'SHIPPED', 'COMPLETED', 'REFUND_FAILED')");
        // 到账累加；不超过实付由表上的 CHECK (refunded_minor <= paid_minor) 兜底。
        assertThat(normalized(sql("addRefunded")))
                .isEqualTo("UPDATE trade_order SET refunded_minor = refunded_minor + #{amountMinor}, status = #{status},"
                        + " version = version + 1, updated_at = #{at} WHERE id = #{orderId}");
        assertThat(normalized(sql("changeOrderStatus")))
                .isEqualTo("UPDATE trade_order SET status = #{status}, version = version + 1, updated_at = #{at}"
                        + " WHERE id = #{orderId} AND status = #{fromStatus}");
        // 已付金额只由支付入账写，退款不碰。
        for (String method : List.of("markOrderRefunding", "addRefunded", "changeOrderStatus")) {
            assertThat(sql(method)).as(method).doesNotContain("paid_minor");
        }
    }

    @Test
    void theRefundChangesOnlyTouchRefundsInTheStatusesTheyLeave() {
        // 受理时间（E06 对账按它分天）只记第一次：已经有的不覆盖。
        assertThat(normalized(sql("markProcessing")))
                .isEqualTo("UPDATE trade_refund SET status = 'PROCESSING', refund_id = #{refundId},"
                        + " accepted_at = COALESCE(accepted_at, #{acceptedAt}),"
                        + " version = version + 1, updated_at = #{at} WHERE id = #{id} AND status = 'REQUESTED'");
        // 异常后人工处理成功的（ABNORMAL → SUCCESS）也能记成功；已关闭、已成功的不行。没给渠道退款单号时保留原来的。
        // 没见过受理就直接到账的，受理时间按到账时间补上；见过的不改。
        assertThat(normalized(sql("markSucceeded")))
                .isEqualTo("UPDATE trade_refund SET status = 'SUCCEEDED', refund_id = COALESCE(#{refundId}, refund_id),"
                        + " succeeded_at = #{succeededAt}, accepted_at = COALESCE(accepted_at, #{succeededAt}),"
                        + " version = version + 1, updated_at = #{at}"
                        + " WHERE id = #{id} AND status IN " + UNFINISHED);
        // 新建、关闭、异常、只更新时间的都不碰受理时间。
        for (String method : List.of("insertRefund", "markFinished", "touch")) {
            assertThat(sql(method)).as(method).doesNotContain("accepted_at");
        }
        assertThat(statements().entrySet().stream()
                .filter(entry -> entry.getValue().contains("accepted_at"))
                .map(Map.Entry::getKey)
                .sorted()
                .toList()).containsExactly("markProcessing", "markSucceeded");
        assertThat(normalized(sql("markFinished")))
                .isEqualTo("UPDATE trade_refund SET status = #{status}, version = version + 1, updated_at = #{at}"
                        + " WHERE id = #{id} AND status IN " + UNFINISHED + " AND status <> #{status}");
        // 只更新时间，不动状态和版本。
        assertThat(normalized(sql("touch"))).isEqualTo("UPDATE trade_refund SET updated_at = #{at} WHERE id = #{id}");
    }

    // 重提已建未提交的；查处理中和异常的（两个截止时间分开给）；最久没动的先，一轮最多 limit 笔。
    @Test
    void theBackstopPicksTheRefundsThatHaveWaitedLongEnough() {
        assertThat(where("findRefundsToSubmit"))
                .isEqualTo("WHERE r.status = 'REQUESTED' AND r.updated_at <= #{before}"
                        + " ORDER BY r.updated_at ASC, r.id ASC LIMIT #{limit}");
        assertThat(where("findRefundsToQuery"))
                .isEqualTo("WHERE (r.status = 'PROCESSING' AND r.updated_at <= #{processingBefore})"
                        + " OR (r.status = 'ABNORMAL' AND r.updated_at <= #{abnormalBefore})"
                        + " ORDER BY r.updated_at ASC, r.id ASC LIMIT #{limit}");
    }

    // ---- 辅助 ----

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : TradeRefundMapper.class.getDeclaredMethods()) {
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
        return Arrays.stream(TradeRefundMapper.class.getDeclaredMethods())
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

    private static List<String> columns(String select, String alias) {
        String text = normalized(select);
        String list = text.substring(text.indexOf("SELECT") + "SELECT".length(), text.indexOf(" FROM "));
        return Arrays.stream(list.split(","))
                .map(String::trim)
                .map(column -> alias.isEmpty() ? column : column.replace(alias, ""))
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
