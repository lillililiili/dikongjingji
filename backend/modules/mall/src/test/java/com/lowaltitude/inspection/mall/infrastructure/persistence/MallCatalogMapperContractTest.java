package com.lowaltitude.inspection.mall.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/** 按现有做法检查 SQL 文本和迁移脚本；真实执行由 dev 环境启动和接口联调覆盖。 */
class MallCatalogMapperContractTest {

    @Test
    void everyQueryBindsItsParametersAndNeverInterpolatesText() {
        List<String> statements = Arrays.stream(MallCatalogMapper.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Select.class))
                .filter(select -> select != null)
                .map(select -> String.join("\n", select.value()))
                .toList();

        assertThat(statements).hasSize(10);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void listAndCountOnlySeePublishedProductsWithAnActiveOfferInTheCity() throws Exception {
        for (String name : List.of("countProducts", "findProducts")) {
            assertThat(sql(name))
                    .contains("p.status = 'PUBLISHED'")
                    .contains("#{category} = 'ALL' OR p.category = #{category}")
                    .contains(MallCatalogMapper.CITY_OFFERS);
        }
        assertThat(MallCatalogMapper.CITY_OFFERS)
                .contains("sku.status = 'ACTIVE'")
                .contains("offer.status = 'ACTIVE'")
                .contains("offer.city_code IS NULL OR offer.city_code = #{cityCode}");
    }

    @Test
    void searchAndFiltersAreBoundAndApplyToBothCountAndList() throws Exception {
        for (String name : List.of("countProducts", "findProducts")) {
            assertThat(sql(name))
                    .startsWith("<script>")
                    .endsWith("</script>")
                    .contains("p.name ILIKE #{keyword} ESCAPE '\\'")
                    .contains("p.summary ILIKE #{keyword} ESCAPE '\\'")
                    .contains("p.sale_mode IN")
                    .contains("p.experience_badge IN")
                    .contains("offer.merchant_kind IN")
                    .contains("city.from_amount_minor &gt;= #{minPriceMinor}")
                    .contains("city.from_amount_minor &lt;= #{maxPriceMinor}")
                    .doesNotContain("${");
        }
    }

    // 文本块拼接时两段之间漏掉空白，会把关键字粘在一起（巡检目录出过 "1FROM"）。
    @Test
    void assembledQueriesKeepTheirKeywordsApart() throws Exception {
        for (String name : List.of("countProducts", "findProducts")) {
            assertThat(sql(name))
                    .doesNotContainPattern("[0-9a-z_)]FROM ")
                    .doesNotContainPattern("[0-9a-z_)]WHERE ")
                    .doesNotContainPattern("[0-9a-z_)]ORDER BY")
                    .doesNotContainPattern("[0-9a-z_)]AND ")
                    .doesNotContainPattern("[0-9a-z_)]<if ");
        }
    }

    @Test
    void sortingIsAWhitelistThatKeepsInquiriesLast() throws Exception {
        assertThat(sql("findProducts"))
                .contains("WHEN #{sort} IN ('PRICE_ASC', 'PRICE_DESC')")
                .contains("city.from_amount_minor IS NULL THEN 1 ELSE 0 END ASC")
                .contains("CASE WHEN #{sort} = 'PRICE_ASC' THEN city.from_amount_minor END ASC")
                .contains("CASE WHEN #{sort} = 'PRICE_DESC' THEN city.from_amount_minor END DESC")
                .contains("p.sort_order ASC")
                .contains("LIMIT #{limit} OFFSET #{offset}");
    }

    @Test
    void offersAreLimitedToTheCityAndListedPricesComeFirst() throws Exception {
        assertThat(sql("findOffers"))
                .contains("sku.product_id = #{productId}")
                .contains("offer.city_code IS NULL OR offer.city_code = #{cityCode}")
                .contains("offer.status = 'ACTIVE'")
                .contains("ORDER BY offer.price_amount_minor ASC NULLS LAST");
        assertThat(sql("findProduct")).contains("status = 'PUBLISHED'");
    }

    @Test
    void migrationCreatesFiveTablesWithTheRequiredColumnsAndNoData() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/mall/V001__mall_catalog.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String table : List.of(
                "mall_product", "mall_product_spec", "mall_product_pack_item", "mall_sku", "mall_offer")) {
            assertThat(migration).contains("CREATE TABLE " + table + " (");
        }
        assertThat(migration)
                .contains("price_amount_minor bigint CHECK (price_amount_minor >= 0)")
                .contains("currency char(3) NOT NULL DEFAULT 'CNY'")
                .contains("city_code varchar(16)");
        // 五张表都要有状态、乐观锁和创建/更新时间（AGENTS.md §12）。
        assertThat(migration.split("version integer NOT NULL DEFAULT 0", -1)).hasSize(6);
        assertThat(migration.split("created_at timestamptz NOT NULL DEFAULT now\\(\\)", -1)).hasSize(6);
        assertThat(migration.split("updated_at timestamptz NOT NULL DEFAULT now\\(\\)", -1)).hasSize(6);
        assertThat(migration.split("\n    status varchar", -1)).hasSize(6);
        assertThat(migration.toUpperCase()).doesNotContain("INSERT INTO");
    }

    private static String sql(String methodName) throws Exception {
        Method method = Arrays.stream(MallCatalogMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return String.join("\n", method.getAnnotation(Select.class).value());
    }
}
