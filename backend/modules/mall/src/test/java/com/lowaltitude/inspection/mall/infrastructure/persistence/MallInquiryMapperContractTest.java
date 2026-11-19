package com.lowaltitude.inspection.mall.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
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
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本（F4 询价）：只用绑定参数；客户的语句以账号为条件、商家的以“被邀请了”为条件；
 * 状态变化都是带前置状态的条件更新；定时任务跳过别的实例锁住的行；读出的列和行记录一一对应。再检查 V003 迁移脚本的约束。
 * 真实执行由一次性数据库上的迁移检查和 infra/scripts/trade-smoke/inquiry.cjs 覆盖。
 */
class MallInquiryMapperContractTest {

    @Test
    void everyStatementBindsItsParametersAndNeverInterpolatesText() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(MallInquiryMapper.class.getDeclaredMethods().length);
        assertThat(statements).hasSize(35);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        // 用 foreach 绑定每个 ID，不拼字符串。
        for (String method : List.of("findOffers", "findInvitations", "findQuotes")) {
            assertThat(normalized(sql(method))).as(method)
                    .startsWith("<script>")
                    .endsWith("</script>")
                    .containsPattern("IN <foreach collection=\"[a-zA-Z]+\" item=\"id\" open=\"\\(\" separator=\",\" "
                            + "close=\"\\)\">#\\{id}</foreach>");
        }
    }

    @Test
    void customerStatementsAreLimitedToTheAccount() {
        for (String method : List.of("findByKey", "findInquiry", "lockInquiry", "countInquiries", "findInquiryPage",
                "findOrderQuote")) {
            assertThat(normalized(sql(method))).as(method).contains("account_id = #{accountId}");
        }
        assertThat(normalized(sql("lockInquiry"))).endsWith("AND i.account_id = #{accountId} FOR UPDATE");
        assertThat(normalized(sql("findOrderQuote")))
                .contains("JOIN mall_inquiry i ON i.id = q.inquiry_id")
                .endsWith("WHERE q.id = #{quoteId} AND i.account_id = #{accountId}");
        // 同一账号的提交排队，幂等键查重不会两份都插进去。
        assertThat(normalized(sql("lockAccount")))
                .contains("pg_advisory_xact_lock(hashtextextended('mall-inquiry:' || CAST(#{accountId} AS text), 0))");
        assertThat(normalized(sql("findInquiryPage")))
                .contains("(CAST(#{status} AS varchar) IS NULL OR i.status = #{status})")
                .endsWith("ORDER BY i.updated_at DESC, i.id DESC LIMIT #{limit} OFFSET #{offset}");
    }

    @Test
    void merchantStatementsOnlyReachInquiriesTheMerchantWasInvitedTo() {
        String invited = "EXISTS (SELECT 1 FROM mall_inquiry_invitation v WHERE v.inquiry_id = i.id"
                + " AND v.merchant_id = #{merchantId})";
        for (String method : List.of("findMerchantInquiry", "lockMerchantInquiry", "countMerchantInquiries",
                "findMerchantInquiryPage")) {
            assertThat(normalized(sql(method))).as(method).contains(invited);
        }
        assertThat(normalized(sql("lockMerchantInquiry"))).endsWith("FOR UPDATE OF i");
        for (String method : List.of("lockInvitation", "markInvitation", "lockLatestQuote", "supersede", "withdraw")) {
            assertThat(normalized(sql(method))).as(method)
                    .contains("inquiry_id = #{inquiryId}")
                    .contains("merchant_id = #{merchantId}");
        }
        // 商家看到的询价单列里没有客户的组织 ID（显示名留给客户自己的视图，服务不放进商家的形状）。
        assertThat(MallInquiryMapper.INQUIRY_COLUMNS).doesNotContain("buyer_organization_id");
    }

    @Test
    void stateChangesAreConditionalUpdatesThatBumpTheVersion() {
        assertThat(normalized(sql("choose")))
                .contains("SET status = 'CHOSEN', chosen_quote_id = #{quoteId}, chosen_at = #{at}")
                .endsWith("WHERE id = #{inquiryId} AND status IN ('OPEN', 'QUOTED') AND version = #{version}");
        assertThat(normalized(sql("close")))
                .contains("SET status = 'CLOSED', close_reason = #{reason}, closed_at = #{at}")
                .endsWith("WHERE id = #{inquiryId} AND status IN ('OPEN', 'QUOTED')");
        assertThat(normalized(sql("changeStatus"))).endsWith("WHERE id = #{inquiryId} AND status = #{fromStatus}");
        // 拒绝是终局：已拒绝的邀请不再改。
        assertThat(normalized(sql("markInvitation"))).endsWith("AND status <> 'DECLINED'")
                .contains("declined_at = CASE WHEN #{status} = 'DECLINED' THEN CAST(#{at} AS timestamptz) END");
        assertThat(normalized(sql("supersede"))).contains("SET status = 'SUPERSEDED'")
                .endsWith("AND status IN ('ACTIVE', 'EXPIRED')");
        assertThat(normalized(sql("withdraw"))).contains("SET status = 'WITHDRAWN'").endsWith("AND status = 'ACTIVE'");
        assertThat(normalized(sql("markChosen"))).contains("SET status = 'CHOSEN'")
                .endsWith("WHERE id = #{quoteId} AND inquiry_id = #{inquiryId} AND status = 'ACTIVE'");
        assertThat(normalized(sql("lapseActive"))).contains("SET status = 'LAPSED'")
                .endsWith("WHERE inquiry_id = #{inquiryId} AND status = 'ACTIVE'");
        assertThat(normalized(sql("recordOrder")))
                .contains("SET last_order_id = #{orderId}, last_ordered_at = #{at}")
                .endsWith("WHERE id = #{quoteId} AND status = 'CHOSEN'");
        for (String method : List.of("choose", "close", "changeStatus", "markInvitation", "supersede", "withdraw",
                "markChosen", "lapseActive", "recordOrder", "expireQuotes")) {
            assertThat(normalized(sql(method))).as(method).contains("version = version + 1");
        }
    }

    @Test
    void quotesAreAppendOnlyAndCustomersSeeTheLatestVersionOnly() {
        assertThat(normalized(sql("insertQuote")))
                .startsWith("INSERT INTO mall_inquiry_quote (")
                .endsWith("RETURNING id");
        assertThat(normalized(sql("lockLatestQuote"))).endsWith("ORDER BY q.quote_version DESC LIMIT 1 FOR UPDATE");
        assertThat(normalized(sql("findQuotes")))
                .contains("AND q.status IN ('ACTIVE', 'EXPIRED', 'WITHDRAWN', 'CHOSEN', 'LAPSED')")
                .doesNotContain("'SUPERSEDED'");
        // 语句里没有删除和改金额的。
        assertThat(statements().values()).noneMatch(sql -> sql.contains("DELETE"))
                .noneMatch(sql -> sql.contains("SET unit_amount_minor"));
        // 用 SELECT 跑的 INSERT/UPDATE … RETURNING 要清缓存，否则同一会话里读到旧的结果。
        for (String method : List.of("insertQuote", "expireQuotes")) {
            Options options = method(method).getAnnotation(Options.class);
            assertThat(options).as(method).isNotNull();
            assertThat(options.flushCache()).isEqualTo(Options.FlushCachePolicy.TRUE);
        }
    }

    @Test
    void theJobPicksDueInquiriesAndSkipsRowsAnotherInstanceHolds() {
        assertThat(normalized(sql("lockDue")))
                .contains("WHERE (i.status = 'OPEN' AND i.quote_deadline <= #{now})")
                .contains("OR (i.status = 'QUOTED' AND EXISTS ( SELECT 1 FROM mall_inquiry_quote q"
                        + " WHERE q.inquiry_id = i.id AND q.status = 'ACTIVE' AND q.valid_until <= #{now}))")
                .endsWith("LIMIT #{limit} FOR UPDATE SKIP LOCKED");
        assertThat(normalized(sql("expireQuotes")))
                .contains("SET status = 'EXPIRED'")
                .endsWith("WHERE inquiry_id = #{inquiryId} AND status = 'ACTIVE' AND valid_until <= #{now} RETURNING id");
    }

    @Test
    void inquiryOffersAreThePublishedInquiryProductsSoldInTheCity() {
        assertThat(normalized(sql("findInquiryOffers")))
                .contains("p.status = 'PUBLISHED'")
                .contains("p.sale_mode = 'INQUIRY'")
                .contains("sku.status = 'ACTIVE'")
                .contains("offer.status = 'ACTIVE'")
                .contains("(offer.city_code IS NULL OR offer.city_code = #{cityCode})");
        // 按 ID 读供给时不过滤：能不能发给它由服务判断，给出明确的拒绝。
        assertThat(normalized(sql("findOffers"))).doesNotContain("'ACTIVE'").doesNotContain("'INQUIRY'");
    }

    // 文本块拼接时两段之间漏掉空白，会把关键字粘在一起（巡检目录出过 "1FROM"）。
    @Test
    void assembledQueriesKeepTheirKeywordsApart() {
        assertThat(statements().values()).allSatisfy(sql -> assertThat(sql)
                .doesNotContainPattern("[0-9a-z_)]FROM ")
                .doesNotContainPattern("[0-9a-z_)]WHERE ")
                .doesNotContainPattern("[0-9a-z_)]AND ")
                .doesNotContainPattern("[0-9a-z_)]JOIN ")
                .doesNotContainPattern("[0-9a-z_)]ORDER BY"));
    }

    // 列别名按下划线转驼峰后正好是行记录的字段，顺序也一致（MyBatis 按构造参数映射）。
    @Test
    void selectedColumnsMatchTheRowRecordsInOrder() {
        assertThat(aliases(MallInquiryMapper.OFFER_COLUMNS)).containsExactlyElementsOf(fields(MallInquiryMapper.OfferRow.class));
        assertThat(aliases(MallInquiryMapper.INQUIRY_COLUMNS))
                .containsExactlyElementsOf(fields(MallInquiryMapper.InquiryRow.class));
        assertThat(aliases(MallInquiryMapper.QUOTE_COLUMNS))
                .containsExactlyElementsOf(fields(MallInquiryMapper.QuoteRow.class));
        assertThat(aliases(sql("findOrderQuote"))).containsExactlyElementsOf(fields(MallInquiryMapper.OrderQuoteRow.class));
        assertThat(aliases(sql("findInvitations").replace("<script>", "")))
                .containsExactlyElementsOf(fields(MallInquiryMapper.InvitationRow.class));
        assertThat(aliases(sql("findByKey"))).containsExactly("id", "requestHash");
        assertThat(fields(MallInquiryMapper.KeyRow.class)).containsExactly("id", "requestHash");
    }

    @Test
    void migrationKeepsTheRulesInTheDatabase() throws Exception {
        String migration = normalized(migration());

        assertThat(migration)
                .contains("CREATE TABLE mall_inquiry (")
                .contains("account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT")
                .contains("requirement varchar(200) NOT NULL")
                .contains("quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 99)")
                .contains("UNIQUE (account_id, idempotency_key)")
                .contains("CONSTRAINT ck_mall_inquiry_chosen CHECK ((status = 'CHOSEN') = (chosen_quote_id IS NOT NULL"
                        + " AND chosen_at IS NOT NULL))")
                .contains("CONSTRAINT ck_mall_inquiry_closed CHECK ((status = 'CLOSED') = (close_reason IS NOT NULL"
                        + " AND closed_at IS NOT NULL))")
                // 一家商家只邀请一次。
                .contains("PRIMARY KEY (inquiry_id, offer_id), UNIQUE (inquiry_id, merchant_id)")
                .contains("CONSTRAINT ck_mall_inquiry_invitation_declined CHECK ( (status = 'DECLINED') ="
                        + " (decline_reason IS NOT NULL AND declined_at IS NOT NULL) )")
                // 报价的硬约束：§25.1 的上限。
                .contains("unit_amount_minor bigint NOT NULL CHECK (unit_amount_minor > 0)")
                .contains("model_text varchar(60) NOT NULL")
                .contains("lead_days integer NOT NULL CHECK (lead_days BETWEEN 1 AND 365)")
                .contains("includes_text varchar(120)")
                .contains("UNIQUE (inquiry_id, merchant_id, quote_version)")
                .contains("CONSTRAINT ck_mall_inquiry_quote_total CHECK (total_minor = unit_amount_minor * quantity)")
                .contains("CONSTRAINT ck_mall_inquiry_quote_validity CHECK (valid_until > quoted_at)")
                .contains("CONSTRAINT ck_mall_inquiry_quote_ordered CHECK (last_order_id IS NULL OR status = 'CHOSEN')")
                .contains("CREATE UNIQUE INDEX uq_mall_inquiry_quote_live ON mall_inquiry_quote (inquiry_id, merchant_id)"
                        + " WHERE status IN ('ACTIVE', 'CHOSEN');")
                .contains("ADD CONSTRAINT fk_mall_inquiry_chosen_quote FOREIGN KEY (chosen_quote_id)"
                        + " REFERENCES mall_inquiry_quote(id) ON DELETE RESTRICT;")
                .contains("CONSTRAINT ck_mall_inquiry_event_order CHECK ((event_type = 'ORDERED') = (order_id IS NOT NULL))");
        // 交易模块的表不引用，迁移也不带数据。
        assertThat(migration).doesNotContain("REFERENCES trade_").doesNotContain("INSERT INTO");
    }

    @Test
    void migrationCodesAreTheServiceEnums() throws Exception {
        String migration = normalized(migration());

        assertThat(migration)
                .contains("status IN (" + codes("MallInquiryStatus") + ")")
                .contains("close_reason IN (" + codes("MallInquiryCloseReason") + ")")
                .contains("status IN (" + codes("MallInquiryInvitationStatus") + ")")
                // SUPERSEDED 只在库里：客户和商家都只看到最新一版。
                .contains("status IN ('ACTIVE', 'SUPERSEDED', 'EXPIRED', 'WITHDRAWN', 'CHOSEN', 'LAPSED')");
    }

    // 这些枚举在应用层包内可见，这里按名字取。
    private static String codes(String enumName) throws ClassNotFoundException {
        Object[] values = Class.forName("com.lowaltitude.inspection.mall.application." + enumName).getEnumConstants();
        return Arrays.stream(values).map(value -> "'" + ((Enum<?>) value).name() + "'").collect(Collectors.joining(", "));
    }

    private static List<String> aliases(String select) {
        String columns = normalized(select);
        columns = columns.substring(columns.indexOf("SELECT ") + "SELECT ".length(), columns.indexOf(" FROM "));
        return Arrays.stream(columns.split(","))
                .map(String::trim)
                .map(column -> column.contains(" ")
                        ? column.substring(column.lastIndexOf(' ') + 1)
                        : column.substring(column.indexOf('.') + 1))
                .map(MallInquiryMapperContractTest::camel)
                .toList();
    }

    private static String camel(String snake) {
        StringBuilder camel = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return camel.toString();
    }

    private static List<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static Method method(String name) {
        return Arrays.stream(MallInquiryMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name)).findFirst().orElseThrow();
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : MallInquiryMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            Update update = method.getAnnotation(Update.class);
            Insert insert = method.getAnnotation(Insert.class);
            String[] text = select != null
                    ? select.value()
                    : update != null ? update.value() : insert != null ? insert.value() : null;
            if (text != null) {
                statements.put(method.getName(), String.join("\n", text));
            }
        }
        return statements;
    }

    private static String sql(String methodName) {
        String sql = statements().get(methodName);
        assertThat(sql).as(methodName).isNotNull();
        return sql;
    }

    private String migration() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/mall/V003__mall_inquiry.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }
}
