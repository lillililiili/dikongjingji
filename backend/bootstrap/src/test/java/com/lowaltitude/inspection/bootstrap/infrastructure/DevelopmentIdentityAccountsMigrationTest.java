package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

class DevelopmentIdentityAccountsMigrationTest {

    private static final String SEED = "/db/development/identity/R__dev_identity_accounts.sql";

    @Test
    void loadsSampleAccountsOnlyInDevFromAnIsolatedFlywayHistory() throws Exception {
        Method migrationMethod = Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("identityDevelopmentDataFlyway"))
                .findFirst()
                .orElseThrow();

        assertThat(migrationMethod.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactly("identityFlyway");

        migrationMethod.setAccessible(true);
        Flyway flyway = (Flyway) migrationMethod.invoke(
                new ModuleFlywayConfiguration(),
                mock(DataSource.class));

        assertThat(flyway.getConfiguration().getTable())
                .isEqualTo("flyway_history_identity_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/identity");
    }

    // 密码公开写在仓库里，所以这份种子只能给出客户、服务商操作员和平台客服，不能给出平台运营权限。
    @Test
    void seedCreatesACustomerAndAProviderOperatorAndNoPlatformOperator() throws Exception {
        String sql = seed();

        assertThat(sql)
                .contains("'dev-customer'")
                .contains("'dev-provider'")
                .contains("role_code = 'CUSTOMER'")
                .contains("role_code = 'PROVIDER_OPERATOR'")
                .doesNotContain("PLATFORM_OPERATOR");
        // 唯一的平台机构是平台客服账号的默认机构。
        assertThat(sql.split("'PLATFORM'", -1)).hasSize(2);
        assertThat(sql).contains("('90000000-0000-4000-8000-000000000001', 'DEV-PLATFORM', '低空产业平台（示例）', 'PLATFORM')");
        assertThat(sql.split("INSERT INTO identity_user \\(", -1)).hasSize(2);
        assertThat(sql).doesNotContain("DELETE FROM").doesNotContain("TRUNCATE");
    }

    // 第三个账号是活动主办场馆的签到员，用来通过受权接口核销报名凭证；它也只是服务商操作员。
    @Test
    void seedCreatesACheckInOperatorForTheVenueThatRunsTheFreeActivity() throws Exception {
        String sql = seed();
        String venue = "a1000000-0000-4000-8000-000000000004";
        String operator = "a1000000-0000-4000-8000-0000000000a4";

        assertThat(sql)
                .contains("'dev-organizer'")
                .contains("('" + venue + "', 'DEV-ACT-VENUE', '东营低空与机器人体验馆', 'PROVIDER')")
                .contains("('" + venue + "', '" + operator + "')")
                .contains("SELECT '" + operator + "', id, '" + venue + "'");
        // 一共四个账号：一个客户、两个服务商操作员、一个平台客服，各自只有一个角色，操作员的角色都限定在自己的机构里。
        assertThat(sql.split("'dev-[a-z]+'", -1)).hasSize(5);
        assertThat(sql.split("role_code = 'CUSTOMER'", -1)).hasSize(2);
        assertThat(sql.split("role_code = 'PROVIDER_OPERATOR'", -1)).hasSize(3);
        assertThat(sql.split("role_code = '", -1)).hasSize(5);
        assertThat(sql.split("INSERT INTO identity_user_role ", -1)).hasSize(5);
        assertThat(sql.split(", id, NULL\n", -1)).hasSize(3);

        // 这家场馆在活动种子里主办并承办免费的开放体验日，所以这个账号核销得了那场活动的凭证。
        String activities;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/activity/R__dev_activity_catalog.sql")) {
            assertThat(stream).isNotNull();
            activities = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
        assertThat(activities)
                .contains("('" + venue + "', 'DEV-ACT-VENUE', '东营低空与机器人体验馆', 'PROVIDER')")
                .contains("('a2000000-0000-4000-8000-000000000005', '" + venue + "', '" + venue + "', 'OPEN_DAY',");
        assertThat(activities.substring(activities.indexOf("('a2000000-0000-4000-8000-000000000005',")))
                .contains("'FREE', NULL, NULL, NULL,")
                .contains("'PUBLISHED')");
    }

    // G1：平台客服只用来读和回复“平台客服”的会话。它只有全局的平台客服角色，没有平台运营的权限。
    @Test
    void seedCreatesAPlatformSupportAccountWithOnlyTheSupportRole() throws Exception {
        String sql = seed();
        String support = "90000000-0000-4000-8000-0000000000a1";

        assertThat(sql)
                .contains("'dev-support'")
                .contains("('90000000-0000-4000-8000-000000000001', '" + support + "')")
                .contains("SELECT '" + support + "', id, NULL\nFROM identity_role WHERE role_code = 'PLATFORM_SUPPORT'");
        assertThat(sql.split("role_code = 'PLATFORM_SUPPORT'", -1)).hasSize(2);
        assertThat(sql.split("crypt\\('test-password'", -1)).hasSize(5);
    }

    // R6-4：原型上标了“资质已审核”的四家示例机构写上审核时间；冲突时只改审核时间，不动别的种子负责的名称；
    // 至少留一家（示例行业装备代理、示例测绘服务商）不写，用来看“不显示”。
    @Test
    void qualificationSeedReviewsTheFourBoardOrganizationsAndLeavesOthersUnreviewed() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
                "/db/development/identity/R__dev_identity_qualification.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql)
                .contains("('22222222-2222-2222-2222-222222222222', 'DEV-PROVIDER-YUNYI', '云翼科技', 'PROVIDER', '2026-11-01 10:00:00+08')")
                .contains("('f2000000-0000-4000-8000-000000000001', 'DEV-PROVIDER-EAGLE', '鹰眼智能巡检', 'PROVIDER', '2026-11-01 10:00:00+08')")
                .contains("('c1000000-0000-4000-8000-000000000001', 'DEV-MALL-ROBOT-MAKER', '示例机器人商家', 'PROVIDER', '2026-11-02 10:00:00+08')")
                .contains("('c1000000-0000-4000-8000-000000000004', 'DEV-MALL-DRONE-MAKER', '示例无人机厂家', 'PROVIDER', '2026-11-02 10:00:00+08')")
                .contains("ON CONFLICT (id) DO UPDATE\nSET qualification_reviewed_at = EXCLUDED.qualification_reviewed_at,\n    updated_at = now();")
                .doesNotContain("'c1000000-0000-4000-8000-000000000005'")
                .doesNotContain("'f2000000-0000-4000-8000-000000000002'")
                .doesNotContain("organization_name = EXCLUDED")
                .doesNotContain("DELETE FROM")
                .doesNotContain("INSERT INTO identity_user ");
        assertThat(sql.split("'PROVIDER', '2026-", -1)).hasSize(5);
    }

    // 生产迁移目录里不能出现任何账号或口令。
    @Test
    void noVersionedMigrationCreatesAnAccount() throws IOException {
        Path modules = Path.of("..", "modules");
        try (Stream<Path> files = Files.walk(modules)) {
            assertThat(files
                    .filter(file -> file.toString().endsWith(".sql"))
                    .filter(file -> file.toString().contains("migrations"))
                    .filter(file -> {
                        try {
                            String sql = Files.readString(file, StandardCharsets.UTF_8);
                            return sql.contains("INSERT INTO identity_user ") || sql.contains("crypt(");
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }))
                    .isEmpty();
        }
    }

    private String seed() throws IOException {
        try (var stream = getClass().getResourceAsStream(SEED)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
