package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

/**
 * F4：开发环境里询价商品“行业测绘无人机”（203）在东营有两家能收款的商家可以报价：示例机器人商家（dev-merchant）新加的一家供给，
 * 示例行业装备代理（新登记收款账户，操作员 dev-merchant-2）原有的一家。只在开发环境加载；询价表随商城迁移在所有环境建。
 */
class DevelopmentMallInquirySeedTest {

    @Test
    void theSampleRobotMerchantOffersTheSurveyDroneForInquiryInDongying() throws Exception {
        String sql = resource("/db/development/mall/R__dev_mall_inquiry_offers.sql");

        assertThat(sql)
                .contains("md5('dev-mall-offer:203:1')::uuid")
                .contains("'c3000000-0000-4000-8000-000000000203'")
                .contains("'c1000000-0000-4000-8000-000000000001'")
                .contains("'370500',\n    NULL,\n    'ITEM'")
                .contains("ON CONFLICT (id) DO UPDATE")
                .doesNotContain("DELETE FROM");
        // 203 本身在商城示例数据里就是询价商品，示例行业装备代理（5）在东营已有一家供给。
        assertThat(resource("/db/development/mall/R__dev_mall_catalog.sql"))
                .contains("('c2000000-0000-4000-8000-000000000203', 'DRONE', '行业测绘无人机',")
                .containsPattern("'c2000000-0000-4000-8000-000000000203', [^\\n]*'INQUIRY', NULL, 90, 'PUBLISHED'\\)")
                .contains("('203', '5', 'AUTHORIZED_DEALER', '370500',");
    }

    @Test
    void theSampleEquipmentAgentCanCollectAndHasItsOwnOperator() throws Exception {
        String sql = resource("/db/development/trade/R__dev_trade_inquiry_merchant.sql");

        assertThat(sql)
                .contains("VALUES ('c1000000-0000-4000-8000-000000000005', '1900000105', true)")
                .contains("'dev-merchant-2'")
                .contains("crypt('test-password', gen_salt('bf', 10))")
                .contains("FROM identity_role WHERE role_code = 'PROVIDER_OPERATOR'")
                .doesNotContain("DELETE FROM");
        // 询价商家的种子只登记 005；示例无人机厂家（004）的账户在 R__dev_trade_merchant_accounts.sql，不在这里重复。
        assertThat(sql).doesNotContain("c1000000-0000-4000-8000-000000000004");
    }

    @Test
    void theSeedsLoadInDevOnlyAndTheTradeSeedAfterTheMallSeed() {
        Method mallSeed = method("mallDevelopmentDataFlyway");
        Method tradeSeed = method("tradeDevelopmentDataFlyway");

        assertThat(mallSeed.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(tradeSeed.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(tradeSeed.getAnnotation(DependsOn.class).value()).contains("mallDevelopmentDataFlyway");
        assertThat(method("mallFlyway").getAnnotation(Profile.class)).isNull();
    }

    private String resource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Method method(String name) {
        return Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
