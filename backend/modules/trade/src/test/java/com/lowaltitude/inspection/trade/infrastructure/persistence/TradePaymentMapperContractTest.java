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
 * 支付单和回调的 SQL 文本（没有可用的数据库测试容器，做法同 TradeOrderMapperContractTest）：只用绑定参数；
 * 买家入口带 buyer_id；锁语句一条只锁一张表的一行；各个状态迁移只改处在允许状态的行；
 * 定时兜底的查询条件；回调按通知 ID 去重、只存摘要。
 */
class TradePaymentMapperContractTest {

    private static final String OPEN = "('CREATED', 'PREPAID')";

    @Test
    void everyMethodCarriesOneStatementThatOnlyBindsParameters() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(TradePaymentMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(22);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql)
                .doesNotContain("${")
                .doesNotContain("<script>"));
    }

    @Test
    void theColumnListsMatchTheRows() {
        assertThat(columns(TradePaymentMapper.PAYMENT_ORDER_COLUMNS, "o."))
                .containsExactlyElementsOf(snakeCase(TradePaymentMapper.PaymentOrderRow.class));
        assertThat(columns(TradePaymentMapper.PAYMENT_COLUMNS, "p."))
                .containsExactlyElementsOf(snakeCase(TradePaymentMapper.PaymentRow.class));
        assertThat(normalized(TradePaymentMapper.PAYMENT_ORDER_COLUMNS)).endsWith("FROM trade_order o");
        assertThat(normalized(TradePaymentMapper.PAYMENT_COLUMNS)).endsWith("FROM trade_payment p");
        for (String method : List.of("lockBuyerOrder", "findBuyerOrder", "lockOrder")) {
            assertThat(sql(method)).as(method).startsWith(TradePaymentMapper.PAYMENT_ORDER_COLUMNS);
        }
        for (String method : List.of(
                "lockOpenPayment", "findOpenPayment", "findPaymentByKey", "lockPayment", "findPaymentByOutTradeNo")) {
            assertThat(sql(method)).as(method).startsWith(TradePaymentMapper.PAYMENT_COLUMNS);
        }
        // 轮询和过期关单带了订单的连接，列清单单独写，也要和 PaymentRow 一一对应。
        for (String method : List.of("findPaymentsToPoll", "findExpiredWithOpenPayment")) {
            assertThat(columns(sql(method), "p."))
                    .as(method)
                    .containsExactlyElementsOf(snakeCase(TradePaymentMapper.PaymentRow.class));
        }
        assertThat(columns(sql("findNotification"), ""))
                .containsExactlyElementsOf(snakeCase(TradePaymentMapper.NotificationRow.class));
    }

    @Test
    void theBuyersEntriesAreLimitedToTheBuyersOwnOrder() {
        assertThat(where("lockBuyerOrder")).isEqualTo("WHERE o.id = #{orderId} AND o.buyer_id = #{buyerId} FOR UPDATE");
        assertThat(where("findBuyerOrder")).isEqualTo("WHERE o.id = #{orderId} AND o.buyer_id = #{buyerId}");
        // 按 ID 锁订单只给回调、查单和定时任务用：调用方已经从支付单或过期列表拿到了订单 ID。
        assertThat(where("lockOrder")).isEqualTo("WHERE o.id = #{orderId} FOR UPDATE");
    }

    // 锁的顺序在服务里固定为先订单后支付单；SQL 这一侧每条锁语句只锁一张表，不连带锁另一张。
    @Test
    void everyLockingStatementLocksRowsOfOneTableOnly() {
        assertThat(where("lockOpenPayment"))
                .isEqualTo("WHERE p.order_id = #{orderId} AND p.status IN " + OPEN + " FOR UPDATE");
        assertThat(where("lockPayment")).isEqualTo("WHERE p.id = #{paymentId} FOR UPDATE");
        for (String method : List.of("lockBuyerOrder", "lockOrder", "lockOpenPayment", "lockPayment")) {
            assertThat(normalized(sql(method))).as(method).doesNotContain("JOIN").endsWith("FOR UPDATE");
        }
        // 只读的查询不加锁。
        for (String method : List.of("findBuyerOrder", "findOpenPayment", "findPaymentByKey", "findPaymentByOutTradeNo",
                "countOutTradeNo", "findPaymentsToPoll", "findExpiredWithOpenPayment", "findNotification")) {
            assertThat(sql(method)).as(method).doesNotContain("FOR UPDATE");
        }
        assertThat(where("findOpenPayment")).isEqualTo("WHERE p.order_id = #{orderId} AND p.status IN " + OPEN);
        assertThat(where("findPaymentByKey"))
                .isEqualTo("WHERE p.order_id = #{orderId} AND p.idempotency_key = #{idempotencyKey}");
        assertThat(where("findPaymentByOutTradeNo")).isEqualTo("WHERE p.out_trade_no = #{outTradeNo}");
        assertThat(normalized(sql("countOutTradeNo")))
                .isEqualTo("SELECT count(*) FROM trade_payment WHERE out_trade_no = #{outTradeNo}");
    }

    @Test
    void aNewPaymentStartsWithTheTableDefaultsAndReturnsItsId() {
        String insert = normalized(sql("insertPayment"));

        assertThat(insert)
                .startsWith("INSERT INTO trade_payment (order_id, channel, out_trade_no, sub_mchid, amount_minor,"
                        + " idempotency_key, created_at, updated_at)")
                .contains("VALUES (#{orderId}, 'WECHAT_APP', #{outTradeNo}, #{subMchid}, #{amountMinor},"
                        + " #{idempotencyKey}, #{at}, #{at})")
                .endsWith("RETURNING id");
        // 状态、预支付、交易号、版本都用表的默认值：新的支付单一定是 CREATED。
        assertThat(insert).doesNotContain("status").doesNotContain("prepay").doesNotContain("transaction_id")
                .doesNotContain("version").doesNotContain("ON CONFLICT");
    }

    @Test
    void everyStatusChangeOnlyTouchesRowsInTheStatusesItLeaves() {
        assertThat(normalized(sql("markPrepaid")))
                .isEqualTo("UPDATE trade_payment SET status = 'PREPAID', prepay_id = #{prepayId},"
                        + " prepay_expires_at = #{expiresAt}, version = version + 1, updated_at = #{at}"
                        + " WHERE id = #{paymentId} AND status = 'CREATED'");
        // 已关闭的支付单也能记成功（关单前一刻付了款）；MISMATCH 和已成功的不行。
        assertThat(normalized(sql("markSucceeded")))
                .isEqualTo("UPDATE trade_payment SET status = 'SUCCEEDED', transaction_id = #{transactionId},"
                        + " succeeded_at = #{succeededAt}, version = version + 1, updated_at = #{at}"
                        + " WHERE id = #{paymentId} AND status IN ('CREATED', 'PREPAID', 'CLOSED')");
        assertThat(normalized(sql("markMismatch")))
                .isEqualTo("UPDATE trade_payment SET status = 'MISMATCH', version = version + 1, updated_at = #{at}"
                        + " WHERE id = #{paymentId} AND status <> 'SUCCEEDED'");
        assertThat(normalized(sql("closePayment")))
                .isEqualTo("UPDATE trade_payment SET status = 'CLOSED', closed_at = #{at}, version = version + 1,"
                        + " updated_at = #{at} WHERE id = #{paymentId} AND status IN " + OPEN);
        // 订单：待付款或已关闭（关单后才到的付款）改成已付款，实付记为应付，关闭时间清掉。
        assertThat(normalized(sql("markOrderPaid")))
                .isEqualTo("UPDATE trade_order SET status = 'PAID', paid_minor = payable_minor, paid_at = #{paidAt},"
                        + " closed_at = NULL, version = version + 1, updated_at = #{at}"
                        + " WHERE id = #{orderId} AND status IN ('PENDING_PAYMENT', 'CLOSED')");
        for (String method : List.of("markPrepaid", "markSucceeded", "markMismatch", "closePayment", "markOrderPaid")) {
            assertThat(normalized(sql(method))).as(method).contains("WHERE id = #{").contains(" AND status");
        }
    }

    // 自提单付款入账时生成取货码（E05）：只写自提单，已经有码的不换。
    @Test
    void aPickupCodeIsWrittenOnceAndOnlyForAPickupOrder() {
        assertThat(normalized(sql("assignPickupCode")))
                .isEqualTo("UPDATE trade_order_delivery SET pickup_code = #{pickupCode}, version = version + 1,"
                        + " updated_at = #{at} WHERE order_id = #{orderId} AND mode = 'PICKUP' AND pickup_code IS NULL");
    }

    @Test
    void pollingPicksPaymentsOlderThanTheCutoffWhoseOrderIsStillPayable() {
        assertThat(normalized(sql("findPaymentsToPoll")))
                .contains("FROM trade_payment p JOIN trade_order o ON o.id = p.order_id")
                .contains("WHERE p.status IN " + OPEN + " AND o.status = 'PENDING_PAYMENT'"
                        + " AND p.created_at <= #{createdBefore} AND o.pay_before > #{now}")
                // 按上次更新排：查不通的被 touch 到后面，不会一直占着一批的名额。
                .endsWith("ORDER BY p.updated_at ASC, p.id ASC LIMIT #{limit}");
    }

    // 过期订单上进行中的支付单：同样按支付单上次更新的时间排，处理失败的被 touch 到后面。
    @Test
    void expiredOrdersWithAnOpenPaymentArePickedLeastRecentlyTriedFirst() {
        assertThat(normalized(sql("findExpiredWithOpenPayment")))
                .contains("FROM trade_payment p JOIN trade_order o ON o.id = p.order_id")
                .contains("WHERE p.status IN " + OPEN + " AND o.status = 'PENDING_PAYMENT'"
                        + " AND o.pay_before <= #{now}")
                .endsWith("ORDER BY p.updated_at ASC, p.id ASC LIMIT #{limit}");
        // 和 TradeOrderMapper.lockExpired 正好互补：那边是没有进行中支付单（NOT EXISTS）的。
        assertThat(normalized(sql("findExpiredWithOpenPayment"))).doesNotContain("NOT EXISTS");
    }

    // touch 只更新时间，不碰状态、版本和别的列。
    @Test
    void touchingAPaymentOnlyMovesItsUpdateTime() {
        assertThat(normalized(sql("touchPayment")))
                .isEqualTo("UPDATE trade_payment SET updated_at = #{at} WHERE id = #{paymentId}");
    }

    @Test
    void aNotificationIsRecordedOncePerIdWithItsSummaryOnly() {
        String insert = normalized(sql("insertNotification"));

        assertThat(insert)
                .startsWith("INSERT INTO trade_channel_notification (channel, notification_id, event_type,"
                        + " signature_valid, out_trade_no, out_refund_no, outcome, received_at)")
                .contains("VALUES ('WECHAT', #{notificationId}, #{eventType}, true, #{outTradeNo}, #{outRefundNo},"
                        + " 'RECEIVED', #{at})")
                .contains("ON CONFLICT (channel, notification_id) DO NOTHING")
                .endsWith("RETURNING id");
        // 不存解密后的内容、密文、请求体、金额或付款人。
        for (String secret : List.of("resource", "ciphertext", "plain", "body", "payer", "amount", "openid", "summary")) {
            assertThat(insert.toLowerCase()).as(secret).doesNotContain(secret);
        }
        assertThat(normalized(sql("findNotification")))
                .isEqualTo("SELECT id, outcome FROM trade_channel_notification"
                        + " WHERE channel = 'WECHAT' AND notification_id = #{notificationId}");
        assertThat(normalized(sql("finishNotification")))
                .isEqualTo("UPDATE trade_channel_notification SET outcome = #{outcome}, detail = #{detail}"
                        + " WHERE id = #{id} AND outcome = 'RECEIVED'");
    }

    // 用 @Select 跑的 INSERT … RETURNING：MyBatis 不会自己清会话缓存，两条都要显式 flushCache。
    @Test
    void bothInsertsReturningAnIdFlushTheSessionCache() throws Exception {
        List<String> inserts = statements().entrySet().stream()
                .filter(entry -> normalized(entry.getValue()).startsWith("INSERT"))
                .map(Map.Entry::getKey)
                .toList();

        assertThat(inserts).containsExactlyInAnyOrder("insertPayment", "insertNotification");
        for (String name : inserts) {
            Method method = method(name);
            assertThat(method.getAnnotation(Select.class)).as(name).isNotNull();
            assertThat(method.getAnnotation(Options.class)).as(name).isNotNull();
            assertThat(method.getAnnotation(Options.class).flushCache())
                    .as(name)
                    .isEqualTo(Options.FlushCachePolicy.TRUE);
        }
    }

    // ---- 辅助 ----

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : TradePaymentMapper.class.getDeclaredMethods()) {
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
        return Arrays.stream(TradePaymentMapper.class.getDeclaredMethods())
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
