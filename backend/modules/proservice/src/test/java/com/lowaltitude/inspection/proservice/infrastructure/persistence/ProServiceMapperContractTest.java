package com.lowaltitude.inspection.proservice.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/** F2 的 SQL 文本和 V001 迁移：只用绑定参数、可见条件、排序白名单、迁移里的约束。规则由 ProServiceReadServiceTest 验证。 */
class ProServiceMapperContractTest {

    @Test
    void everyStatementUsesBoundParametersOnlyAndOnlyReads() {
        List<String> statements = Arrays.stream(ProServiceMapper.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(ProServiceMapperContractTest::sql)
                .toList();

        assertThat(statements).hasSize(9);
        assertThat(statements).allSatisfy(sql -> assertThat(sql)
                .doesNotContain("${")
                .doesNotContainIgnoringCase("insert ")
                .doesNotContainIgnoringCase("update ")
                .doesNotContainIgnoringCase("delete "));
    }

    // 目录、详情、服务商主页都只给已上架、在这个城市上架（城市行在用）的服务。
    @Test
    void everyCustomerReadGoesThroughTheCityVisibility() throws Exception {
        for (String name : List.of("countCatalog", "findCatalog", "countOfferedInCity", "findVisible",
                "findProviderServices")) {
            assertThat(sql(method(name)))
                    .contains("s.status = 'PUBLISHED'")
                    .contains("c.city_code = #{cityCode}")
                    .contains("c.status = 'ACTIVE'");
        }
        assertThat(sql(method("findVisible"))).contains("s.id = #{serviceId}");
        assertThat(sql(method("findProviderServices"))).contains("s.provider_id = #{providerId}");
        assertThat(sql(method("countOfferedInCity"))).contains("s.type = #{type}").doesNotContain("keyword");
        for (String name : List.of("findProviderCities", "findProviderIdsIn")) {
            assertThat(sql(method(name))).contains("s.status = 'PUBLISHED'").contains("c.status = 'ACTIVE'");
        }
        assertThat(sql(method("findCities"))).contains("status = 'ACTIVE'");
    }

    @Test
    void catalogFiltersAreBoundAndTheKeywordIsEscaped() throws Exception {
        for (String name : List.of("countCatalog", "findCatalog")) {
            assertThat(sql(method(name)))
                    .startsWith("<script>")
                    .contains("s.type = #{type}")
                    .contains("s.title ILIKE #{keyword} ESCAPE '\\'")
                    .contains("s.summary ILIKE #{keyword} ESCAPE '\\'")
                    .contains("tag.kind = 'TAG'")
                    .contains("facet.kind = 'FACET'")
                    .contains("facet.code IN")
                    .contains("s.price_mode = #{priceMode}")
                    .doesNotContain("<>")
                    .doesNotContain(" < ");
        }
    }

    // 排序只有三种固定写法；按价格排时按项目报价的在最后（false 在前）。
    @Test
    void sortingIsAWhitelistWithProjectPricingLast() throws Exception {
        String sql = sql(method("findCatalog"));

        assertThat(sql)
                .contains("<when test=\"sort == 'PRICE_ASC'\">")
                .contains("ORDER BY (s.price_mode = 'PROJECT') ASC, s.price_amount_minor ASC, s.sort_order ASC, s.id ASC")
                .contains("<when test=\"sort == 'PRICE_DESC'\">")
                .contains("ORDER BY (s.price_mode = 'PROJECT') ASC, s.price_amount_minor DESC, s.sort_order ASC, s.id ASC")
                .contains("<otherwise>")
                .contains("ORDER BY s.sort_order ASC, s.id ASC")
                .contains("LIMIT #{limit} OFFSET #{offset}");
        assertThat(sql.split("ORDER BY", -1)).hasSize(4);
    }

    @Test
    void selectedColumnsMatchTheRowRecord() {
        String list = ProServiceMapper.SERVICE_COLUMNS.substring(
                ProServiceMapper.SERVICE_COLUMNS.indexOf("SELECT") + 6, ProServiceMapper.SERVICE_COLUMNS.indexOf("FROM"));
        List<String> columns = Arrays.stream(list.split(","))
                .map(String::trim)
                .map(column -> column.substring(column.indexOf('.') + 1))
                .toList();
        List<String> fields = Arrays.stream(ProServiceMapper.ServiceRow.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(name -> name.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT))
                .toList();

        assertThat(columns).containsExactlyElementsOf(fields);
        assertThat(sqlOf("findItems")).contains("SELECT service_id, kind, code, label, detail");
    }

    @Test
    void migrationCreatesTheThreeTablesWithTheirRules() throws Exception {
        String migration = migration();

        assertThat(migration)
                .contains("CREATE TABLE pro_service (")
                .contains("provider_id uuid NOT NULL REFERENCES identity_organization(id)")
                .contains("type IN ('AERIAL', 'SURVEY', 'MAINTENANCE', 'TECH_SUPPORT')")
                .contains("price_mode IN ('PRICE_FROM', 'PROJECT')")
                .contains("price_amount_minor bigint CHECK (price_amount_minor > 0)")
                .contains("ck_pro_service_price")
                .contains("cover_url IS NULL OR cover_url LIKE 'https://%'")
                .contains("sample boolean NOT NULL DEFAULT false")
                .contains("status IN ('PUBLISHED', 'UNPUBLISHED')")
                .contains("CREATE TABLE pro_service_city (")
                .contains("PRIMARY KEY (service_id, city_code)")
                .contains("CREATE TABLE pro_service_item (")
                .contains("ck_pro_service_item_code")
                .contains("ck_pro_service_item_detail")
                .contains("uq_pro_service_item_facet")
                .contains("version integer NOT NULL DEFAULT 0")
                .contains("created_at timestamptz NOT NULL DEFAULT now()")
                // 金额只存分；不引用巡检模块的表；不带数据。
                .doesNotContain("numeric")
                .doesNotContain("double")
                .doesNotContain("REFERENCES inspection_")
                .doesNotContain("INSERT INTO");
    }

    // 迁移里的编码和 Java 的枚举一致（类型、场景分类、筛选项）。
    @Test
    void migrationCodesMatchTheJavaEnums() throws Exception {
        String migration = migration();

        assertThat(codes(migration, "type IN \\(([^)]*)\\)")).containsExactly("AERIAL", "SURVEY", "MAINTENANCE",
                "TECH_SUPPORT");
        assertThat(codes(migration, "category_code IN \\(([^)]*)\\)")).containsExactlyElementsOf(
                javaEnum("ProServiceCategory"));
        assertThat(codes(migration, "CHECK \\(code IN \\(([^)]*)\\)")).containsExactlyElementsOf(javaEnum("ProServiceFacet"));
        assertThat(codes(migration, "type IN \\(([^)]*)\\)")).containsExactlyElementsOf(javaEnum("ProServiceType"));
    }

    private static List<String> javaEnum(String simpleName) throws ClassNotFoundException {
        Class<?> type = Class.forName("com.lowaltitude.inspection.proservice.application." + simpleName);
        return Arrays.stream(type.getEnumConstants()).map(value -> ((Enum<?>) value).name()).toList();
    }

    private static List<String> codes(String migration, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(migration);
        assertThat(matcher.find()).as(regex).isTrue();
        return Arrays.stream(matcher.group(1).split(",")).map(code -> code.replaceAll("[\\s']", "")).toList();
    }

    private String migration() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/proservice/V001__proservice.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sqlOf(String name) {
        try {
            return sql(method(name));
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Method method(String name) throws NoSuchMethodException {
        return Arrays.stream(ProServiceMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(name));
    }

    private static String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select == null) {
            throw new AssertionError("no SQL on " + method.getName());
        }
        return String.join("\n", select.value());
    }
}
