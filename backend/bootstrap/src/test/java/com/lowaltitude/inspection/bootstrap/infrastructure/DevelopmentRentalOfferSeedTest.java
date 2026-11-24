package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

/** F3：示例机器人商家（dev-merchant 的机构，登记了收款账户）出租巡检机器狗，只在开发环境加载；租赁表在所有环境迁移。 */
class DevelopmentRentalOfferSeedTest {

    @Test
    void theSampleMerchantRentsTheRobotDogInDevOnly() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/development/mall/R__dev_mall_rental_offers.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }

        assertThat(sql)
                .contains("md5('dev-mall-offer:401:1')::uuid")
                .contains("'c3000000-0000-4000-8000-000000000401'")
                .contains("'c1000000-0000-4000-8000-000000000001'")
                .contains("38000,\n    'DAY'")
                .contains("ON CONFLICT (id) DO UPDATE")
                .doesNotContain("DELETE FROM");
        try (var stream = getClass().getResourceAsStream("/db/development/trade/R__dev_trade_merchant_accounts.sql")) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("('c1000000-0000-4000-8000-000000000001', '1900000101', true)");
        }
        Method mallSeed = method("mallDevelopmentDataFlyway");
        assertThat(mallSeed.getAnnotation(Profile.class).value()).containsExactly("dev");
    }

    @Test
    void rentalTablesMigrateAfterInspectionInEveryEnvironment() {
        Method migrationMethod = method("rentalFlyway");

        assertThat(migrationMethod.getAnnotation(Profile.class)).isNull();
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactly("inspectionFlyway");
    }

    private static Method method(String name) {
        return Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
