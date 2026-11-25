package com.lowaltitude.inspection.mall.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/** 运营端商品读写的 SQL 文本和迁移 V004（S3-1）。真实执行在开发环境联调过。 */
class MallCatalogOpsMapperContractTest {

    private static List<String> statements() {
        return Arrays.stream(MallCatalogOpsMapper.class.getDeclaredMethods())
                .map(MallCatalogOpsMapperContractTest::sql)
                .filter(sql -> sql != null)
                .toList();
    }

    private static String sql(Method method) {
        if (method.getAnnotation(Select.class) != null) {
            return String.join("\n", method.getAnnotation(Select.class).value());
        }
        if (method.getAnnotation(Insert.class) != null) {
            return String.join("\n", method.getAnnotation(Insert.class).value());
        }
        if (method.getAnnotation(Update.class) != null) {
            return String.join("\n", method.getAnnotation(Update.class).value());
        }
        if (method.getAnnotation(Delete.class) != null) {
            return String.join("\n", method.getAnnotation(Delete.class).value());
        }
        return null;
    }

    @Test
    void everyStatementBindsItsParametersAndScriptsStayValidXml() {
        assertThat(statements()).isNotEmpty().allSatisfy(sql -> {
            assertThat(sql).doesNotContain("${");
            if (sql.startsWith("<script>")) {
                // 动态 SQL 是 XML：不能有裸的 < 和 <>（开发环境启动时踩过）。
                assertThat(sql.replaceAll("</?(script|if|foreach)[^>]*>", "")).doesNotContain("<");
            }
        });
    }

    @Test
    void writesThatReturnIdsFlushTheSessionCache() {
        for (Method method : MallCatalogOpsMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            if (select != null && String.join("", select.value()).contains("RETURNING")) {
                assertThat(method.getAnnotation(Options.class)).as(method.getName()).isNotNull();
                assertThat(method.getAnnotation(Options.class).flushCache())
                        .isEqualTo(Options.FlushCachePolicy.TRUE);
            }
        }
    }

    @Test
    void deletedProductsAreNeverManaged() throws Exception {
        assertThat(sql(MallCatalogOpsMapper.class.getMethod("findProduct", java.util.UUID.class)))
                .contains("status <> 'DELETED'");
        assertThat(MallCatalogOpsMapper.LIST_FILTER).contains("p.status != 'DELETED'");
    }

    @Test
    void deletingOnlyMarksADraft() throws Exception {
        assertThat(sql(MallCatalogOpsMapper.class.getMethod("deleteDraft", java.util.UUID.class, int.class,
                java.time.OffsetDateTime.class)))
                .contains("SET status = 'DELETED'").contains("version = #{version}").contains("AND status = 'DRAFT'");
        try (var stream = getClass().getResourceAsStream("/db/migration/mall/V007__mall_product_delete.sql")) {
            assertThat(stream).isNotNull();
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).contains("'PRODUCT_DELETE'");
        }
    }

    @Test
    void migrationAddsStockSellingPointsAndAnAuditTrailWithoutTouchingData() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/mall/V004__mall_ops_catalog.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String statements = sql.replaceAll("(?m)--.*$", "");
        assertThat(statements)
                .contains("ADD COLUMN stock_quantity integer CHECK (stock_quantity IS NULL OR stock_quantity >= 0)")
                .contains("CREATE TABLE mall_product_selling_point")
                .contains("CREATE TABLE mall_catalog_operation")
                .contains("CREATE UNIQUE INDEX uq_mall_offer_sku_merchant_city")
                .doesNotContain("DELETE FROM")
                .doesNotContain("UPDATE mall_")
                .doesNotContain("DROP ");
    }
}
