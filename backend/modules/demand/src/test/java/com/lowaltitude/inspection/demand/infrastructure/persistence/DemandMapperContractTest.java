package com.lowaltitude.inspection.demand.infrastructure.persistence;

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

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * F1 的 SQL 文本和 V001 迁移：只用绑定参数、按发布账号或服务商限定、条件更新、加锁，以及迁移里的约束。
 * 规则本身由 DemandServiceTest 用内存 mapper 验证，真实库上由迁移检查和冒烟验证。
 */
class DemandMapperContractTest {

    @Test
    void everyStatementUsesBoundParametersOnly() {
        List<String> statements = Arrays.stream(DemandMapper.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic() && !method.isDefault())
                .map(DemandMapperContractTest::sql)
                .toList();

        assertThat(statements).hasSize(42);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    // 1.3.0：计数按账号；服务订单只挂在已选定、还没挂过的需求上；明细按报价整组替换。
    @Test
    void quoteDetailsCountsAndServiceOrderLinksAreScoped() throws Exception {
        assertThat(sql(method("countByStatus"))).contains("WHERE account_id = #{accountId}").contains("GROUP BY status");
        assertThat(sql(method("linkServiceOrder"))).contains("status = 'CHOSEN'")
                .contains("service_order_id IS NULL");
        assertThat(sql(method("deleteQuoteLines"))).contains("WHERE quote_id = #{quoteId}");
        assertThat(sql(method("updateQuoteDetails"))).contains("WHERE id = #{quoteId}");
        assertThat(sql(method("findQuoteLines"))).contains("ORDER BY quote_id, sort_order");
    }

    // 客户的读写都以发布账号为条件：别人的需求读不到、改不了、删不掉。
    @Test
    void customerStatementsAreScopedToThePublishingAccount() throws Exception {
        for (String name : List.of("findDemand", "lockDemand", "updateDraft", "deleteDraft", "findByKey")) {
            assertThat(sql(method(name))).contains("account_id = #{accountId}");
        }
        assertThat(sql(method("countDemands"))).contains("d.account_id = #{accountId}")
                .contains("CAST(#{status} AS varchar) IS NULL OR d.status = #{status}");
        assertThat(sql(method("findDemandPage"))).contains("d.account_id = #{accountId}")
                .contains("ORDER BY d.updated_at DESC, d.id DESC")
                .contains("LIMIT #{limit} OFFSET #{offset}");
        assertThat(sql(method("lockDemand"))).contains("FOR UPDATE");
        // 同一个账号新建需求排队，幂等键在锁里查。
        assertThat(sql(method("lockAccount"))).contains("pg_advisory_xact_lock").contains("'demand:'");
    }

    // 草稿才能改、删、提交；状态变化都是带前置状态的条件更新，版本号加 1。
    @Test
    void stateChangesAreConditionalUpdates() throws Exception {
        assertThat(sql(method("updateDraft"))).contains("status = 'DRAFT'").contains("version = version + 1");
        assertThat(sql(method("deleteDraft"))).contains("DELETE FROM demand").contains("status = 'DRAFT'");
        assertThat(sql(method("submit")))
                .contains("SET status = 'OPEN'")
                .contains("submitted_at = #{at}")
                .contains("quote_deadline = #{deadline}")
                .contains("AND status = 'DRAFT'");
        assertThat(sql(method("changeStatus"))).contains("AND status = #{fromStatus}").contains("version = version + 1");
        assertThat(sql(method("close")))
                .contains("SET status = 'CLOSED'")
                .contains("close_reason = #{reason}")
                .contains("AND status IN ('OPEN', 'QUOTED')");
        assertThat(sql(method("choose")))
                .contains("SET status = 'CHOSEN'")
                .contains("chosen_quote_version = #{quoteVersion}")
                .contains("AND status IN ('OPEN', 'QUOTED')")
                .contains("AND version = #{version}");
        assertThat(sql(method("lockExpiredOpen")))
                .contains("d.status = 'OPEN'")
                .contains("d.quote_deadline <= #{now}")
                .contains("LIMIT #{limit}")
                .contains("FOR UPDATE SKIP LOCKED");
    }

    // 服务商：草稿看不到；还在收报价的看平台匹配（服务城市）或邀请；报过价的一直能看（看结果）。
    @Test
    void providersSeeOpenDemandsTheyMatchOrAreInvitedToAndTheOnesTheyQuoted() throws Exception {
        for (String name : List.of("countProviderDemands", "findProviderDemands")) {
            assertThat(sql(method(name)))
                    .startsWith("<script>")
                    .contains("d.status IN ('OPEN', 'QUOTED', 'CHOSEN', 'CLOSED')")
                    .contains("d.quote_scope = 'MATCH' AND d.city_code IN")
                    .contains("i.demand_id = d.id AND i.provider_id = #{providerId}")
                    .contains("q.demand_id = d.id AND q.provider_id = #{providerId}")
                    // 1.2.0：可按状态筛，在能看到的范围之内。
                    .contains("<if test=\"status != null\">")
                    .contains("AND d.status = #{status}")
                    // 在 <script> 里：XML 不认小于号。
                    .doesNotContain("<>")
                    .doesNotContain(" < ");
        }
        assertThat(sql(method("findProviderDemands"))).contains("ORDER BY d.submitted_at DESC, d.id DESC");
        for (String name : List.of("lockSubmittedDemand", "findSubmittedDemand")) {
            assertThat(sql(method(name))).doesNotContain("<script>").contains("d.status <> 'DRAFT'");
        }
        assertThat(sql(method("lockSubmittedDemand"))).contains("FOR UPDATE");
        assertThat(sql(method("lockProviderQuote")))
                .contains("q.demand_id = #{demandId}")
                .contains("q.provider_id = #{providerId}")
                .contains("FOR UPDATE");
        assertThat(sql(method("findProviderQuotes"))).contains("q.provider_id = #{providerId}");
    }

    @Test
    void quoteStatementsOnlyTouchQuotesInTheExpectedState() throws Exception {
        assertThat(sql(method("insertQuote"))).contains("INSERT INTO demand_quote").contains("RETURNING id");
        // 用 @Select 跑 INSERT … RETURNING：必须清缓存，否则同一会话里第二次报价会拿到缓存的 ID。
        assertThat(method("insertQuote").getAnnotation(Options.class).flushCache())
                .isEqualTo(Options.FlushCachePolicy.TRUE);
        assertThat(sql(method("reviseQuote")))
                .contains("SET status = 'ACTIVE'")
                .contains("quote_version = quote_version + 1")
                .contains("AND status IN ('ACTIVE', 'WITHDRAWN')");
        assertThat(sql(method("withdrawQuote"))).contains("SET status = 'WITHDRAWN'").contains("AND status = 'ACTIVE'");
        assertThat(sql(method("markChosen")))
                .contains("SET status = 'CHOSEN'")
                .contains("AND demand_id = #{demandId}")
                .contains("AND status = 'ACTIVE'")
                .contains("AND quote_version = #{quoteVersion}");
        assertThat(sql(method("endActiveQuotes"))).contains("SET status = #{toStatus}").contains("AND status = 'ACTIVE'");
        assertThat(sql(method("findQuotes"))).contains("ORDER BY q.first_submitted_at ASC, q.id ASC");
    }

    // 读出来的列和行记录的字段一一对应（MyBatis 按构造参数顺序映射）。
    @Test
    void selectedColumnsMatchTheRowRecords() {
        assertThat(columns(DemandMapper.DEMAND_COLUMNS)).containsExactlyElementsOf(fields(DemandMapper.DemandRow.class));
        assertThat(columns(DemandMapper.QUOTE_COLUMNS)).containsExactlyElementsOf(fields(DemandMapper.QuoteRow.class));
    }

    @Test
    void migrationCreatesTheDemandTablesWithTheirRules() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/demand/V001__demand.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains("CREATE TABLE demand (")
                .contains("account_id uuid NOT NULL REFERENCES identity_user(id)")
                .contains("type IN ('INSPECTION', 'AERIAL', 'SURVEY', 'MAINTENANCE', 'TECH_SUPPORT')")
                .contains("status IN ('DRAFT', 'OPEN', 'QUOTED', 'CHOSEN', 'CLOSED')")
                .contains("quote_scope IN ('MATCH', 'INVITE')")
                .contains("close_reason IN ('NO_QUOTES', 'CANCELLED')")
                .contains("address varchar(80)")
                .contains("scope_text varchar(60)")
                .contains("note varchar(500)")
                .contains("UNIQUE (account_id, idempotency_key)")
                .contains("ck_demand_budget")
                .contains("ck_demand_submitted")
                .contains("ck_demand_chosen")
                .contains("ck_demand_closed")
                .contains("CREATE TABLE demand_deliverable")
                .contains("CREATE TABLE demand_invitation")
                .contains("CREATE TABLE demand_quote")
                .contains("status IN ('ACTIVE', 'WITHDRAWN', 'CHOSEN', 'NOT_CHOSEN', 'CLOSED')")
                .contains("amount_minor bigint NOT NULL CHECK (amount_minor > 0)")
                .contains("method varchar(40) NOT NULL")
                .contains("schedule_text varchar(60) NOT NULL")
                .contains("duration_days integer NOT NULL CHECK (duration_days BETWEEN 1 AND 3650)")
                .contains("deliverables_text varchar(120) NOT NULL")
                .contains("UNIQUE (demand_id, provider_id)")
                .contains("CREATE TABLE demand_event")
                .contains("ck_demand_event_actor")
                .contains("ck_demand_event_quote")
                // 金额只存分，没有浮点；本模块不引用巡检模块的表；不带数据。
                .doesNotContain("numeric")
                .doesNotContain("double")
                .doesNotContain("REFERENCES inspection_")
                .doesNotContain("INSERT INTO");
        // 每张业务表都有主键和创建时间。
        for (String table : List.of("demand", "demand_quote", "demand_event")) {
            assertThat(migration).containsPattern("CREATE TABLE " + table + " \\(\\s+id uuid PRIMARY KEY");
        }
    }

    // 交付物白名单：迁移里的编码和 Java 的枚举一致。
    @Test
    void migrationDeliverablesMatchTheJavaWhitelist() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/demand/V001__demand.sql")) {
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher block = Pattern.compile("code IN \\(([^)]*)\\)").matcher(migration);
        assertThat(block.find()).isTrue();
        List<String> codes = Arrays.stream(block.group(1).split(","))
                .map(code -> code.replaceAll("[\\s']", ""))
                .toList();
        assertThat(codes).containsExactlyInAnyOrderElementsOf(Arrays.stream(
                        com.lowaltitude.inspection.demand.application.DemandDeliverable.values())
                .map(Enum::name)
                .toList());
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
        return Arrays.stream(DemandMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(name));
    }

    private static String sql(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation instanceof Select select) {
                return String.join("\n", select.value());
            }
            if (annotation instanceof Insert insert) {
                return String.join("\n", insert.value());
            }
            if (annotation instanceof Update update) {
                return String.join("\n", update.value());
            }
            if (annotation instanceof Delete delete) {
                return String.join("\n", delete.value());
            }
        }
        throw new AssertionError("no SQL on " + method.getName());
    }
}
