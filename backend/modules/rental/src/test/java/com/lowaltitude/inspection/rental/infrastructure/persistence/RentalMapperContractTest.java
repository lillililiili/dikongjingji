package com.lowaltitude.inspection.rental.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * F3 的 SQL 文本和 V001 迁移：只用绑定参数、客户按账号、商家按出租机构限定、条件更新、加锁，以及迁移里的约束。
 * 规则由 RentalServiceTest 用内存 mapper 验证，真实库上由迁移检查和冒烟脚本 rental.cjs 验证。
 */
class RentalMapperContractTest {

    @Test
    void everyStatementUsesBoundParametersOnly() {
        List<String> statements = Arrays.stream(RentalMapper.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && !method.isDefault())
                .map(RentalMapperContractTest::sql)
                .toList();

        assertThat(statements).hasSize(45);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void customerAndMerchantReadsAreScoped() throws Exception {
        for (String name : List.of("findInquiry", "lockInquiry", "countInquiries", "findInquiryPage", "findByKey")) {
            assertThat(sql(method(name))).contains("account_id = #{accountId}");
        }
        for (String name : List.of("findMerchantInquiry", "lockMerchantInquiry", "countMerchantInquiries",
                "findMerchantInquiryPage")) {
            assertThat(sql(method(name))).contains("i.merchant_id = #{merchantId}");
        }
        for (String name : List.of("findOrder", "lockOrder", "countOrders", "findOrderPage")) {
            assertThat(sql(method(name))).contains("o.account_id = #{accountId}");
        }
        for (String name : List.of("findMerchantOrder", "lockMerchantOrder", "countMerchantOrders",
                "findMerchantOrderPage")) {
            assertThat(sql(method(name))).contains("o.merchant_id = #{merchantId}");
        }
        // 交易回调：租赁订单和交易订单一起对上才算。
        assertThat(sql(method("lockPaidOrder")))
                .contains("o.id = #{orderId} AND o.trade_order_id = #{tradeOrderId} FOR UPDATE");
        for (String name : List.of("lockInquiry", "lockMerchantInquiry", "lockOrder", "lockMerchantOrder")) {
            assertThat(sql(method(name))).endsWith("FOR UPDATE");
        }
        assertThat(sql(method("lockAccount"))).contains("pg_advisory_xact_lock").contains("'rental:'");
    }

    @Test
    void stateChangesAreConditionalUpdates() throws Exception {
        for (String name : List.of("closeInquiry", "declineInquiry", "markQuoted")) {
            assertThat(sql(method(name))).contains("AND status IN ('PENDING', 'QUOTED', 'EXPIRED')")
                    .contains("version = version + 1");
        }
        assertThat(sql(method("expireInquiry"))).contains("SET status = 'EXPIRED'").contains("AND status = 'QUOTED'");
        assertThat(sql(method("confirmInquiry"))).contains("SET status = 'CONFIRMED'").contains("AND status = 'QUOTED'")
                .contains("AND version = #{version}");
        assertThat(sql(method("markPaid"))).contains("SET status = 'AWAITING_HANDOVER'")
                .contains("AND status = 'PENDING_PAYMENT'");
        assertThat(sql(method("closeUnpaid"))).contains("SET status = 'CLOSED'").contains("AND status = 'PENDING_PAYMENT'");
        assertThat(sql(method("moveOrder"))).contains("AND status = #{fromStatus}")
                .contains("<if test=\"toStatus == 'IN_USE'\">rent_started_at = #{at},</if>")
                .contains("<if test=\"toStatus == 'ENDED'\">ended_at = #{at},</if>");
        assertThat(sql(method("cancelOrder"))).contains("SET status = 'CANCELLED'")
                .contains("AND status IN ('AWAITING_HANDOVER', 'HANDOVER_RECORDED')");
        // 客户确认之后记录不再改。
        assertThat(sql(method("saveRecord"))).contains("ON CONFLICT (order_id, kind) DO UPDATE")
                .contains("WHERE rental_record.confirmed_at IS NULL");
        assertThat(sql(method("confirmRecord"))).contains("AND confirmed_at IS NULL");
    }

    @Test
    void jobsSkipRowsOtherInstancesHold() throws Exception {
        assertThat(sql(method("lockUnquoted"))).contains("i.status = 'PENDING'").contains("i.quote_deadline <= #{now}")
                .contains("FOR UPDATE SKIP LOCKED");
        assertThat(sql(method("lockLapsedQuotes"))).contains("i.status = 'QUOTED'").contains("q.valid_until <= #{now}")
                .contains("q.quote_version = i.current_quote_version").contains("FOR UPDATE OF i SKIP LOCKED");
    }

    // 合同快照从询价单和那一版报价生成：到期日 = 起租日 + 天数 − 1。
    @Test
    void theContractIsASnapshotOfTheInquiryAndTheQuote() throws Exception {
        assertThat(sql(method("insertContract")))
                .contains("INSERT INTO rental_contract")
                .contains("i.start_date + q.days - 1")
                .contains("JOIN rental_quote q ON q.inquiry_id = i.id AND q.id = #{quoteId}")
                .contains("WHERE i.id = #{inquiryId}");
        assertThat(method("insertQuote").getAnnotation(Options.class).flushCache())
                .isEqualTo(Options.FlushCachePolicy.TRUE);
        assertThat(sql(method("insertQuote"))).contains("RETURNING id");
    }

    @Test
    void selectedColumnsMatchTheRowRecords() {
        assertThat(columns(RentalMapper.INQUIRY_COLUMNS)).containsExactlyElementsOf(fields(RentalMapper.InquiryRow.class));
        assertThat(columns(RentalMapper.QUOTE_COLUMNS)).containsExactlyElementsOf(fields(RentalMapper.QuoteRow.class));
        assertThat(columns(RentalMapper.ORDER_COLUMNS)).containsExactlyElementsOf(fields(RentalMapper.OrderRow.class));
        assertThat(columns(RentalMapper.CONTRACT_COLUMNS)).containsExactlyElementsOf(fields(RentalMapper.ContractRow.class));
    }

    @Test
    void migrationCreatesTheRentalTablesWithTheirRules() throws Exception {
        String migration = migration();

        assertThat(migration)
                .contains("CREATE TABLE rental_inquiry (")
                .contains("quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 10)")
                .contains("days integer NOT NULL CHECK (days BETWEEN 1 AND 90)")
                .contains("handover_mode IN ('DELIVERY', 'PICKUP')")
                .contains("return_mode IN ('COLLECT', 'DROP_OFF')")
                .contains("status IN ('PENDING', 'QUOTED', 'EXPIRED', 'CONFIRMED', 'DECLINED', 'CLOSED')")
                .contains("UNIQUE (account_id, idempotency_key)")
                .contains("ck_rental_inquiry_closed")
                .contains("CREATE TABLE rental_quote (")
                .contains("UNIQUE (inquiry_id, quote_version)")
                .contains("CHECK (rent_minor = daily_rent_minor * days * quantity)")
                .contains("CREATE TABLE rental_quote_fee (")
                .contains("amount_minor bigint NOT NULL CHECK (amount_minor >= 0)")
                .contains("CREATE TABLE rental_order (")
                .contains("trade_order_id uuid NOT NULL UNIQUE")
                .contains("inquiry_id uuid NOT NULL UNIQUE")
                .contains("ck_rental_order_cancelled")
                .contains("CREATE TABLE rental_contract (")
                .contains("contract_no varchar(24) NOT NULL UNIQUE")
                .contains("CHECK (end_date = start_date + days - 1)")
                .contains("CREATE TABLE rental_record (")
                .contains("UNIQUE (order_id, kind)")
                .contains("CREATE TABLE rental_event (")
                .contains("ck_rental_event_actor")
                // 金额只存分；不引用商城、账号、交易模块的表；不带数据。
                .doesNotContain("numeric")
                .doesNotContain("REFERENCES mall_")
                .doesNotContain("REFERENCES account_")
                .doesNotContain("REFERENCES trade_")
                .doesNotContain("INSERT INTO");
    }

    // 迁移里订单状态和 Java 的枚举一致。
    @Test
    void migrationStatusesMatchTheJavaEnums() throws Exception {
        Matcher matcher = Pattern.compile("status IN \\('PENDING_PAYMENT'[^)]*\\)").matcher(migration());
        assertThat(matcher.find()).isTrue();
        List<String> codes = Arrays.stream(matcher.group().replace("status IN (", "").replace(")", "").split(","))
                .map(code -> code.replaceAll("[\\s']", "")).toList();
        Class<?> type = Class.forName("com.lowaltitude.inspection.rental.application.RentalOrderStatus");
        assertThat(codes).containsExactlyElementsOf(Arrays.stream(type.getEnumConstants())
                .map(value -> ((Enum<?>) value).name()).toList());
    }

    private String migration() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/rental/V001__rental.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> columns(String select) {
        String list = select.substring(select.indexOf("SELECT") + 6, select.indexOf("FROM"));
        return Arrays.stream(list.split(","))
                .map(String::trim)
                .map(column -> column.substring(column.indexOf('.') + 1))
                .toList();
    }

    private static List<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT))
                .toList();
    }

    private static Method method(String name) throws NoSuchMethodException {
        return Arrays.stream(RentalMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(name));
    }

    private static String sql(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation instanceof Select select) {
                return String.join("\n", select.value()).trim();
            }
            if (annotation instanceof Insert insert) {
                return String.join("\n", insert.value()).trim();
            }
            if (annotation instanceof Update update) {
                return String.join("\n", update.value()).trim();
            }
        }
        throw new AssertionError("no SQL on " + method.getName());
    }
}
