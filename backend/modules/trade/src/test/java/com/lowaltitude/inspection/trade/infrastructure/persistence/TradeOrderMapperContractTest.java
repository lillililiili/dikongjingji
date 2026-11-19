package com.lowaltitude.inspection.trade.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本：只用绑定参数；买家的读写都以 buyer_id 为条件；
 * 提交排队用事务级 advisory lock；超时关单跳过别人锁住的行；关单只改仍待付款、版本对得上的那一张。
 * 真实执行由 dev 环境启动和接口联调覆盖；迁移脚本由 TradeMigrationContractTest 检查。
 */
class TradeOrderMapperContractTest {

    @Test
    void everyMethodCarriesOneStatementThatOnlyBindsParameters() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(TradeOrderMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(33);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        // 动态 SQL 只有四处 IN 列表（行、配送、退款、关闭原因）、买家订单的状态过滤（foreach 绑定）和商家订单的状态过滤（#{} 绑定）。
        assertThat(statements).allSatisfy((method, sql) -> {
            if (sql.contains("<foreach")) {
                assertThat(sql).as(method).startsWith("<script>").contains("</script>");
            }
        });
    }

    @Test
    void submissionsOfOneBuyerQueueOnATransactionLock() {
        assertThat(normalized(sql("lockBuyer")))
                .contains("pg_advisory_xact_lock(hashtextextended('trade-order:' || CAST(#{buyerId} AS text), 0))");
        // 事务级的锁随事务结束释放，不用会话级的 pg_advisory_lock。
        assertThat(sql("lockBuyer")).doesNotContain("pg_advisory_lock(");
    }

    @Test
    void anIdempotencyKeyIsLookedUpWithinTheBuyer() {
        assertThat(normalized(sql("findByKey")))
                .contains("SELECT id, request_hash FROM trade_order")
                .contains("WHERE buyer_id = #{buyerId} AND idempotency_key = #{idempotencyKey}");
    }

    @Test
    void everyBuyerReadIsLimitedToTheBuyersOwnOrders() {
        assertThat(normalized(sql("findBuyerOrder")))
                .contains("WHERE o.id = #{orderId} AND o.buyer_id = #{buyerId}")
                .doesNotContain("FOR UPDATE");
        assertThat(normalized(sql("lockBuyerOrder")))
                .contains("WHERE o.id = #{orderId} AND o.buyer_id = #{buyerId} FOR UPDATE");
        for (String method : List.of("countBuyerOrders", "findBuyerOrders")) {
            assertThat(normalized(sql(method)))
                    .as(method)
                    .contains("WHERE o.buyer_id = #{buyerId}")
                    .contains("<if test=\"statuses != null and statuses.size() > 0\">")
                    .contains("AND o.status IN <foreach collection=\"statuses\" item=\"status\" open=\"(\""
                            + " separator=\",\" close=\")\">#{status}</foreach>");
        }
        assertThat(normalized(sql("findBuyerOrders")))
                .contains("ORDER BY o.created_at DESC, o.id DESC LIMIT #{limit} OFFSET #{offset}");
        // 三条买家读取用同一份列清单，和 OrderRow 的字段一一对应。
        for (String method : List.of("findBuyerOrder", "lockBuyerOrder", "findOrder", "findBuyerOrders",
                "findMerchantOrder", "lockMerchantOrder", "findMerchantOrders")) {
            assertThat(sql(method).replace("<script>", "")).as(method).startsWith(TradeOrderMapper.ORDER_COLUMNS);
        }
        assertThat(columns(TradeOrderMapper.ORDER_COLUMNS, "o."))
                .containsExactlyElementsOf(snakeCase(TradeOrderMapper.OrderRow.class));
    }

    @Test
    void childRowsAreReadForTheGivenOrdersOnlyAndInAStableOrder() {
        assertThat(normalized(sql("findLines")))
                .contains("FROM trade_order_line WHERE order_id IN <foreach collection=\"orderIds\"")
                .contains("ORDER BY sort_order ASC, id ASC");
        assertThat(normalized(sql("findDeliveries")))
                .contains("FROM trade_order_delivery WHERE order_id IN <foreach collection=\"orderIds\"");
        assertThat(normalized(sql("findRefunds")))
                .contains("FROM trade_refund WHERE order_id IN <foreach collection=\"orderIds\"")
                .contains("ORDER BY created_at ASC, id ASC");
        assertThat(selectedColumns(sql("findLines"))).containsExactlyElementsOf(snakeCase(TradeOrderMapper.LineRow.class));
        assertThat(selectedColumns(sql("findDeliveries")))
                .containsExactlyElementsOf(snakeCase(TradeOrderMapper.DeliveryRow.class));
        assertThat(selectedColumns(sql("findRefunds")))
                .containsExactlyElementsOf(snakeCase(TradeOrderMapper.RefundRow.class));
    }

    // 已关闭订单的关闭原因：每张单最后一次进入 CLOSED 的那条迁移记录（关单后才到的付款会重新打开订单）。
    @Test
    void theCloseReasonIsTheCauseOfTheLastMoveIntoClosed() {
        assertThat(normalized(sql("findCloseCauses")))
                .startsWith("<script>")
                .contains("SELECT DISTINCT ON (order_id) order_id, cause FROM trade_state_transition")
                .contains("WHERE to_status = 'CLOSED' AND order_id IN <foreach collection=\"orderIds\" item=\"id\""
                        + " open=\"(\" separator=\",\" close=\")\">#{id}</foreach>")
                .contains("ORDER BY order_id, occurred_at DESC, created_at DESC, id DESC")
                .endsWith("</script>");
        String sql = normalized(sql("findCloseCauses"));
        String prefix = "SELECT DISTINCT ON (order_id) ";
        String selected = sql.substring(sql.indexOf(prefix) + prefix.length(), sql.indexOf(" FROM "));
        assertThat(Arrays.stream(selected.split(",")).map(String::trim).toList())
                .containsExactlyElementsOf(snakeCase(TradeOrderMapper.CloseCauseRow.class));
    }

    @Test
    void closingAnUnpaidOrderChecksItsStatusAndVersion() {
        assertThat(normalized(sql("closeUnpaid")))
                .contains("UPDATE trade_order SET status = 'CLOSED', closed_at = #{at}, version = version + 1,"
                        + " updated_at = #{at}")
                .contains("WHERE id = #{orderId} AND status = 'PENDING_PAYMENT' AND version = #{version}");
    }

    @Test
    void expiredOrdersAreLockedOldestFirstSkippingRowsAnotherInstanceHolds() {
        assertThat(normalized(sql("lockExpired")))
                .contains("SELECT o.id, o.version FROM trade_order o")
                .contains("WHERE o.status = 'PENDING_PAYMENT' AND o.pay_before <= #{now}")
                .contains("ORDER BY o.pay_before ASC, o.id ASC LIMIT #{limit} FOR UPDATE OF o SKIP LOCKED");
        // 只锁订单行（子查询里的支付单不锁），并且只到 LIMIT 为止。
        assertThat(normalized(sql("lockExpired"))).endsWith("FOR UPDATE OF o SKIP LOCKED");
    }

    // 发起过支付的过期订单要先向渠道查单，由支付服务关；这里只关没有进行中支付单的。
    @Test
    void expiredOrdersWithAnOpenPaymentAreLeftToThePaymentService() {
        assertThat(normalized(sql("lockExpired")))
                .contains("AND NOT EXISTS ( SELECT 1 FROM trade_payment p WHERE p.order_id = o.id"
                        + " AND p.status IN ('CREATED', 'PREPAID'))");
        assertThat(normalized(sql("countOpenPayments")))
                .isEqualTo("SELECT count(*) FROM trade_payment WHERE order_id = #{orderId}"
                        + " AND status IN ('CREATED', 'PREPAID')");
    }

    @Test
    void aNewOrderTakesItsBuyerFromTheParameterAndIsAlwaysAMallOrder() {
        String insert = normalized(sql("insertOrder"));

        assertThat(insert)
                .contains("INSERT INTO trade_order ( order_no, buyer_id, merchant_id, merchant_kind, merchant_name,"
                        + " sub_mchid, source, city_code, delivery_mode, goods_minor, shipping_minor, payable_minor,"
                        + " buyer_note, pay_before, idempotency_key, request_hash, created_at, updated_at )")
                .contains("VALUES ( #{orderNo}, #{buyerId}, #{merchantId}, #{merchantKind}, #{merchantName},"
                        + " #{subMchid}, 'MALL', #{cityCode}, #{deliveryMode}, #{goodsMinor}, #{shippingMinor},"
                        + " #{payableMinor}, #{buyerNote}, #{payBefore}, #{idempotencyKey}, #{requestHash}, #{at}, #{at} )")
                .contains("RETURNING id");
        // 状态、已付、已退、版本都用表的默认值：新单一定是待付款。
        assertThat(insert).doesNotContain("status").doesNotContain("paid_minor").doesNotContain("version");
        assertThat(normalized(sql("countOrderNo"))).contains("SELECT count(*) FROM trade_order WHERE order_no = #{orderNo}");
        assertThat(normalized(sql("lockOrderNo")))
                .contains("pg_advisory_xact_lock(hashtextextended('trade-order-no:' || #{orderNo}, 0))");
    }

    @Test
    void snapshotsAndTransitionsAreWrittenFromParametersOnly() {
        assertThat(normalized(sql("insertLine")))
                .contains("INSERT INTO trade_order_line ( order_id, product_id, offer_id, product_name, sku_name,"
                        + " cover_url, unit_amount_minor, quantity, subtotal_minor, sort_order, created_at )");
        assertThat(normalized(sql("insertDelivery")))
                .contains("INSERT INTO trade_order_delivery ( order_id, mode, recipient_name, phone, province_name,"
                        + " city_name, address_detail, pickup_point, created_at, updated_at )");
        assertThat(normalized(sql("insertTransition")))
                .contains("INSERT INTO trade_state_transition (order_id, from_status, to_status, cause, actor_id,"
                        + " occurred_at)")
                .contains("VALUES (#{orderId}, #{fromStatus}, #{toStatus}, #{cause}, #{actorId}, #{at})");
        for (String method : List.of("insertOrder", "insertLine", "insertDelivery", "insertTransition")) {
            assertThat(sql(method)).as(method).doesNotContain("SELECT ").doesNotContain("WHERE");
        }
    }

    @Test
    void aMerchantAccountIsUpsertedPerOrganizationAndItsVersionBumped() {
        assertThat(normalized(sql("saveMerchantAccount")))
                .contains("INSERT INTO trade_merchant_account (organization_id, sub_mchid, active, created_at,"
                        + " updated_at)")
                .contains("ON CONFLICT (organization_id) DO UPDATE SET sub_mchid = EXCLUDED.sub_mchid,"
                        + " active = EXCLUDED.active, version = trade_merchant_account.version + 1,"
                        + " updated_at = EXCLUDED.updated_at")
                .contains("RETURNING organization_id, sub_mchid, active");
        assertThat(normalized(sql("findMerchantAccount")))
                .contains("FROM trade_merchant_account WHERE organization_id = #{organizationId}");
        assertThat(normalized(sql("countOrganization")))
                .contains("FROM identity_organization WHERE id = #{organizationId}");
    }

    // ---- 商家（E05） ----

    // 商家的读写都以 merchant_id 为条件（机构只从令牌里取），不看 buyer_id；别的机构的订单查不到。
    @Test
    void everyMerchantReadIsLimitedToTheMerchantsOwnOrders() {
        assertThat(where("findMerchantOrder")).isEqualTo("WHERE o.id = #{orderId} AND o.merchant_id = #{merchantId}");
        assertThat(where("lockMerchantOrder"))
                .isEqualTo("WHERE o.id = #{orderId} AND o.merchant_id = #{merchantId} FOR UPDATE");
        for (String method : List.of("countMerchantOrders", "findMerchantOrders")) {
            assertThat(normalized(sql(method)))
                    .as(method)
                    .startsWith("<script>")
                    .contains("WHERE o.merchant_id = #{merchantId} <if test=\"status != null\"> AND o.status = #{status} </if>")
                    .doesNotContain("buyer_id = #{")
                    .doesNotContain("<foreach");
        }
        assertThat(normalized(sql("countMerchantOrders"))).startsWith("<script>SELECT count(*) FROM trade_order o WHERE");
        assertThat(normalized(sql("findMerchantOrders")))
                .endsWith("ORDER BY o.created_at DESC, o.id DESC LIMIT #{limit} OFFSET #{offset} </script>");
        for (String method : List.of("findMerchantOrder", "lockMerchantOrder")) {
            assertThat(sql(method)).as(method).doesNotContain("buyer_id = #{");
        }
    }

    @Test
    void shippingOnlyMovesAPaidExpressOrderAtItsVersionAndSavesTheTracking() {
        assertThat(normalized(sql("markShipped")))
                .isEqualTo("UPDATE trade_order SET status = 'SHIPPED', shipped_at = #{at}, version = version + 1,"
                        + " updated_at = #{at} WHERE id = #{orderId} AND status = 'PAID' AND delivery_mode = 'EXPRESS'"
                        + " AND version = #{version}");
        assertThat(normalized(sql("saveTracking")))
                .isEqualTo("UPDATE trade_order_delivery SET carrier_name = #{carrierName}, tracking_no = #{trackingNo},"
                        + " version = version + 1, updated_at = #{at} WHERE order_id = #{orderId}");
    }

    // 确认收货（已发货）和自提核销（已付款）共用一条：起始状态由调用方给出，版本也要对得上。
    @Test
    void completingAnOrderChecksTheStatusItLeavesAndItsVersion() {
        assertThat(normalized(sql("markCompleted")))
                .isEqualTo("UPDATE trade_order SET status = 'COMPLETED', completed_at = #{at}, version = version + 1,"
                        + " updated_at = #{at} WHERE id = #{orderId} AND status = #{fromStatus} AND version = #{version}");
    }

    // 取货码和输错次数一起锁住读；输错一次加一，到 10 次就不再加（表上 CHECK 0..10 兜底）。
    @Test
    void pickupFailuresAreCountedUnderALockAndStopAtTen() {
        assertThat(normalized(sql("lockPickup")))
                .isEqualTo("SELECT pickup_code, pickup_failures FROM trade_order_delivery"
                        + " WHERE order_id = #{orderId} FOR UPDATE");
        assertThat(selectedColumns(sql("lockPickup")))
                .containsExactlyElementsOf(snakeCase(TradeOrderMapper.PickupRow.class));
        assertThat(normalized(sql("recordPickupFailure")))
                .isEqualTo("UPDATE trade_order_delivery SET pickup_failures = pickup_failures + 1, updated_at = #{at}"
                        + " WHERE order_id = #{orderId} AND pickup_failures < 10");
    }

    // 自动确认收货：发货满期限的已发货单，最早发货的先，跳过别的实例正在处理的行。
    @Test
    void ordersDueForReceiptAreLockedOldestFirstSkippingRowsAnotherInstanceHolds() {
        assertThat(normalized(sql("lockReceiptDue")))
                .isEqualTo("SELECT id, version FROM trade_order WHERE status = 'SHIPPED'"
                        + " AND shipped_at <= #{shippedBefore} ORDER BY shipped_at ASC, id ASC LIMIT #{limit}"
                        + " FOR UPDATE SKIP LOCKED");
        assertThat(selectedColumns(sql("lockReceiptDue")))
                .containsExactlyElementsOf(snakeCase(TradeOrderMapper.ExpiredRow.class));
    }

    // ---- 辅助 ----

    /** WHERE 起到结尾。 */
    private static String where(String methodName) {
        String text = normalized(sql(methodName));
        return text.substring(text.indexOf("WHERE"));
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : TradeOrderMapper.class.getDeclaredMethods()) {
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

    private static String sql(String methodName) {
        String sql = statements().get(methodName);
        assertThat(sql).as(methodName).isNotNull();
        return sql;
    }

    /** SELECT 和 FROM 之间的列名，去掉表别名。 */
    private static List<String> selectedColumns(String sql) {
        String text = normalized(sql);
        return columns(text.substring(text.indexOf("SELECT"), text.indexOf(" FROM ")), "");
    }

    private static List<String> columns(String select, String alias) {
        String text = normalized(select);
        String list = text.substring(text.indexOf("SELECT") + "SELECT".length(),
                text.contains(" FROM ") ? text.indexOf(" FROM ") : text.length());
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

    /** 去掉注释、把连续空白并成一个空格，换行和缩进的调整不影响断言。 */
    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }
}
