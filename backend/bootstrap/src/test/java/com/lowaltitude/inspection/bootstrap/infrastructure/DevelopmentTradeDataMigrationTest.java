package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

/**
 * 交易的开发种子（db/development/trade）：只在 dev 环境、用自己的 Flyway 历史表加载，排在交易迁移和商城示例数据之后。
 * E05 加的 dev-merchant 是示例机器人商家的操作员，只给 PROVIDER_OPERATOR，不给平台权限。
 */
class DevelopmentTradeDataMigrationTest {

    private static final String OPERATOR_SEED = "db/development/trade/R__dev_trade_merchant_operator.sql";
    private static final String ROBOT_MAKER = "c1000000-0000-4000-8000-000000000001";
    private static final String DEV_MERCHANT = "c1000000-0000-4000-8000-0000000000a1";

    @Test
    void loadsTradeSeedsOnlyInDevFromAnIsolatedFlywayHistory() throws Exception {
        Method migrationMethod = Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("tradeDevelopmentDataFlyway"))
                .findFirst()
                .orElseThrow();

        assertThat(migrationMethod.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value())
                .containsExactlyInAnyOrder("tradeFlyway", "mallDevelopmentDataFlyway", "activityDevelopmentDataFlyway",
                        "inspectionDevelopmentDataFlyway");

        migrationMethod.setAccessible(true);
        Flyway flyway = (Flyway) migrationMethod.invoke(new ModuleFlywayConfiguration(), mock(DataSource.class));

        assertThat(flyway.getConfiguration().getTable()).isEqualTo("flyway_history_trade_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/trade");
    }

    // 种子只在开发数据目录里：正式迁移目录（所有环境都跑）里没有它。
    @Test
    void theMerchantOperatorSeedLivesOnlyUnderDevelopmentData() {
        ClassLoader loader = getClass().getClassLoader();

        assertThat(loader.getResource(OPERATOR_SEED)).isNotNull();
        assertThat(loader.getResource("db/migration/trade/R__dev_trade_merchant_operator.sql")).isNull();
        assertThat(loader.getResource("db/migration/R__dev_trade_merchant_operator.sql")).isNull();
    }

    // dev-merchant：示例机器人商家的操作员，只有 PROVIDER_OPERATOR，账号、成员、角色都挂在这家机构下；可以重复执行。
    @Test
    void devMerchantIsAProviderOperatorOfTheSampleRobotMaker() throws Exception {
        String sql = read(OPERATOR_SEED);
        String mall = read("db/development/mall/R__dev_mall_catalog.sql");

        assertThat(mall).contains("('" + ROBOT_MAKER + "', 'DEV-MALL-ROBOT-MAKER', '示例机器人商家', 'PROVIDER')");
        assertThat(sql)
                .contains("'dev-merchant'")
                .contains("FROM identity_role WHERE role_code = 'PROVIDER_OPERATOR'")
                .doesNotContain("PLATFORM_OPERATOR")
                .doesNotContain("'CUSTOMER'")
                .doesNotContain("DELETE FROM")
                .doesNotContain("TRUNCATE");
        // 只有一种角色。
        assertThat(sql.split("role_code = ", -1)).hasSize(2);
        // 默认机构、成员关系、角色的机构范围都是示例机器人商家。
        String user = section(sql, "INSERT INTO identity_user (", "INSERT INTO identity_organization_member");
        String member = section(sql, "INSERT INTO identity_organization_member", "INSERT INTO identity_user_role");
        String role = sql.substring(sql.indexOf("INSERT INTO identity_user_role"));
        assertThat(user).contains("'" + DEV_MERCHANT + "'").contains("'" + ROBOT_MAKER + "'");
        assertThat(member).contains("VALUES ('" + ROBOT_MAKER + "', '" + DEV_MERCHANT + "')");
        assertThat(role).contains("SELECT '" + DEV_MERCHANT + "', id, '" + ROBOT_MAKER + "'");
        // 重复执行不报错：三条都有冲突处理。
        assertThat(user).contains("ON CONFLICT (id) DO UPDATE");
        assertThat(member).contains("ON CONFLICT (organization_id, user_id) DO UPDATE");
        assertThat(role).contains("ON CONFLICT DO NOTHING");
        // 密码只存哈希。
        assertThat(user).contains("crypt('test-password', gen_salt('bf', 10))");
    }

    // E09：收费示例活动的两家主办机构（活动开发数据建的）登记了收款账户，子商户号和商城示例商家的错开；可以重复执行。
    @Test
    void theOrganizersOfThePaidSampleActivitiesCanCollectPayments() throws Exception {
        String sql = read("db/development/trade/R__dev_trade_activity_merchant_accounts.sql");
        String activities = read("db/development/activity/R__dev_activity_catalog.sql");
        String mallAccounts = read("db/development/trade/R__dev_trade_merchant_accounts.sql");

        assertThat(sql)
                .contains("('a1000000-0000-4000-8000-000000000001', '1900000201', true)")
                .contains("('a1000000-0000-4000-8000-000000000002', '1900000202', true)")
                .contains("ON CONFLICT (organization_id) DO UPDATE")
                .doesNotContain("DELETE FROM");
        assertThat(activities)
                .contains("('a1000000-0000-4000-8000-000000000001', 'DEV-ACT-YOUTH-TECH'")
                .contains("('a1000000-0000-4000-8000-000000000002', 'DEV-ACT-STUDY'");
        assertThat(mallAccounts).doesNotContain("1900000201").doesNotContain("1900000202");
        assertThat(getClass().getClassLoader()
                .getResource("db/migration/trade/R__dev_trade_activity_merchant_accounts.sql")).isNull();
    }

    // E10：两家场馆的运营机构登记了收款账户；dev-organizer 是体验馆的操作员，能核销这家场馆的到馆码。
    @Test
    void theVenueOperatorsCanCollectPaymentsAndDevOrganizerWorksForTheExperienceHall() throws Exception {
        String sql = read("db/development/trade/R__dev_trade_venue_merchant_accounts.sql");
        String venues = read("db/development/experience/R__dev_experience_catalog.sql");
        String identity = read("db/development/identity/R__dev_identity_accounts.sql");

        assertThat(sql)
                .contains("('a1000000-0000-4000-8000-000000000004', '1900000204', true)")
                .contains("('a1000000-0000-4000-8000-000000000005', '1900000205', true)")
                .contains("ON CONFLICT (organization_id) DO UPDATE");
        assertThat(venues)
                .contains("('b2000000-0000-4000-8000-000000000001', 'a1000000-0000-4000-8000-000000000004',")
                .contains("('b2000000-0000-4000-8000-000000000002', 'a1000000-0000-4000-8000-000000000005',");
        assertThat(identity).contains("SELECT 'a1000000-0000-4000-8000-0000000000a4', id, "
                + "'a1000000-0000-4000-8000-000000000004'");
        // 重排场次时已约人数把已有的有效预约算进去，之后取消、关单不会减成负数。
        assertThat(venues).contains("b.status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN')");
        for (String other : List.of("R__dev_trade_merchant_accounts.sql", "R__dev_trade_activity_merchant_accounts.sql")) {
            assertThat(read("db/development/trade/" + other)).doesNotContain("1900000204").doesNotContain("1900000205");
        }
    }

    private static String section(String sql, String from, String to) {
        return sql.substring(sql.indexOf(from), sql.indexOf(to));
    }

    private String read(String path) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
