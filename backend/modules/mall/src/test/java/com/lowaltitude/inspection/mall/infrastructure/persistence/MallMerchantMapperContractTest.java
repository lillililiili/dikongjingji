package com.lowaltitude.inspection.mall.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 商家资料与商家主页（S5-2）的 SQL 文本检查：只用绑定参数；审核只改仍待审核、版本对得上的那一版；
 * 对外资料只由审核通过的那一版整份写入；一家的提交排队。
 */
class MallMerchantMapperContractTest {

    @Test
    void everyMethodCarriesOneStatementThatOnlyBindsParameters() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(MallMerchantMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        assertThat(statements).allSatisfy((method, sql) -> {
            if (sql.contains("<where") || sql.contains("<if")) {
                assertThat(sql).as(method).startsWith("<script>").contains("</script>");
            }
        });
    }

    @Test
    void reviewingOnlyClosesAPendingSubmissionOfTheRightVersion() {
        assertThat(normalized("review")).contains("WHERE id = #{id} AND status = 'SUBMITTED' AND version = #{version}");
        assertThat(normalized("publish")).contains("FROM mall_merchant_profile_submission s WHERE s.id = #{submissionId}")
                .contains("ON CONFLICT (organization_id) DO UPDATE");
    }

    @Test
    void submissionsOfOneOrganizationQueue() {
        assertThat(normalized("lockOrganization"))
                .contains("pg_advisory_xact_lock(hashtextextended('mall-merchant-profile:' || CAST(#{organizationId} AS text), 0))");
        assertThat(normalized("supersedePending"))
                .contains("WHERE organization_id = #{organizationId} AND status = 'SUBMITTED'");
    }

    @Test
    void theMerchantPageOnlyCountsPublishedProductsAndActiveOffers() {
        assertThat(normalized("findOfferSpread"))
                .contains("product.status = 'PUBLISHED'")
                .contains("sku.status = 'ACTIVE'")
                .contains("offer.status = 'ACTIVE'");
    }

    private static String normalized(String method) {
        return statements().get(method).replaceAll("\\s+", " ").trim();
    }

    private static Map<String, String> statements() {
        return Arrays.stream(MallMerchantMapper.class.getDeclaredMethods())
                .collect(Collectors.toMap(Method::getName, MallMerchantMapperContractTest::statement));
    }

    private static String statement(Method method) {
        Select select = method.getAnnotation(Select.class);
        if (select != null) {
            assertThat(select.value()).as(method.getName()).hasSize(1);
            return select.value()[0];
        }
        Update update = method.getAnnotation(Update.class);
        assertThat(update).as(method.getName()).isNotNull();
        assertThat(update.value()).as(method.getName()).hasSize(1);
        return update.value()[0];
    }
}
