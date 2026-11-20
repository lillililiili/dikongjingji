package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

/**
 * M20：示例直播间只在开发环境加载，排在主办机构和 dev-merchant 之后；直播表在所有环境迁移。
 * 主办机构、讲解项都是别的开发数据里真实存在的 ID；流是本机模拟服务的，播放地址是 App 内的示例视频。
 */
class DevelopmentLiveSeedTest {

    private static final String SEED = "/db/development/live/R__dev_live.sql";

    @Test
    void theSeedLoadsInDevOnlyAfterTheDataItPointsAt() throws Exception {
        Method seed = method("liveDevelopmentDataFlyway");

        assertThat(seed.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(seed.getAnnotation(DependsOn.class).value()).containsExactlyInAnyOrder("liveFlyway",
                "identityDevelopmentDataFlyway", "inspectionDevelopmentDataFlyway", "mallDevelopmentDataFlyway",
                "tradeDevelopmentDataFlyway");
        seed.setAccessible(true);
        Flyway flyway = (Flyway) seed.invoke(new ModuleFlywayConfiguration(), mock(DataSource.class));
        assertThat(flyway.getConfiguration().getTable()).isEqualTo("flyway_history_live_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/live");
        assertThat(flyway.getConfiguration().getPlaceholders()).containsKey(ModuleFlywayConfiguration.SEED_DATE_PLACEHOLDER);
        assertThat(seed()).contains("${" + ModuleFlywayConfiguration.SEED_DATE_PLACEHOLDER + "}");

        Method migration = method("liveFlyway");
        assertThat(migration.getAnnotation(Profile.class)).isNull();
        assertThat(migration.getAnnotation(DependsOn.class).value()).containsExactly("inspectionFlyway");
    }

    @Test
    void theBoardRoomsAreTwoLiveTwoPreviewsAndTwoReplays() throws Exception {
        String sql = seed();

        for (String title : List.of("机器狗园区夜巡演示", "编程无人机开箱与首飞", "编程机器人入门", "园区夜巡方案怎么定：服务商答疑",
                "光伏电站热斑巡检现场连线", "机器人避障闯关：体验馆开放日")) {
            assertThat(sql).as(title).contains("'" + title + "'");
        }
        assertThat(count(sql, "'LIVE', now() - interval")).isEqualTo(2);
        assertThat(count(sql, "'SCHEDULED', NULL, NULL, NULL,")).isEqualTo(2);
        assertThat(count(sql, "'ENDED', (DATE")).isEqualTo(2);
        assertThat(count(sql, "'https://live.example.com/replay/")).isEqualTo(2);
        // 没有授权图片：封面一律为空；播放地址只用 App 里的两段示例视频。
        assertThat(sql).doesNotContain("https://cover").doesNotContain("http://");
        Matcher play = Pattern.compile("'(/static/videos/preview/[^']+)'").matcher(sql);
        while (play.find()) {
            assertThat(play.group(1)).isIn("/static/videos/preview/live-night-patrol.mp4",
                    "/static/videos/preview/live-drone-unboxing.mp4");
        }
        // 主办机构是已有的示例机构：鹰眼智能巡检、示例无人机厂家、示例机器人商家、云翼科技、东营低空与机器人体验馆。
        for (String host : List.of("'f2000000-0000-4000-8000-000000000001', '鹰眼智能巡检'",
                "'c1000000-0000-4000-8000-000000000004', '示例无人机厂家'",
                "'c1000000-0000-4000-8000-000000000001', '示例机器人商家'",
                "'22222222-2222-2222-2222-222222222222', '云翼科技'",
                "'a1000000-0000-4000-8000-000000000004', '东营低空与机器人体验馆'")) {
            assertThat(sql).contains(host);
        }
        // 先删再插，可以重复执行；不碰别的直播间。
        assertThat(sql).contains("DELETE FROM live_room WHERE id IN (");
        assertThat(count(sql, "DELETE FROM")).isEqualTo(1);
    }

    @Test
    void theExplainerItemsPointAtRealDevelopmentObjects() throws Exception {
        String sql = seed();

        assertThat(sql)
                .contains("'INSPECTION_SERVICE', '77777777-7777-7777-7777-777777777777', '园区安防巡检'")
                .contains("'PRODUCT', 'c2000000-0000-4000-8000-000000000401', '巡检机器狗'")
                .contains("'PRODUCT', 'c2000000-0000-4000-8000-000000000201', '编程教学无人机 入门套装'")
                .contains("'PRODUCT', 'c2000000-0000-4000-8000-000000000301', '智能飞行电池 适配教学机'")
                .contains("'PRODUCT', 'c2000000-0000-4000-8000-000000000101', '编程机器人探索套装'");
        assertThat(resource("/db/development/inspection/R__dev_inspection_catalog.sql"))
                .contains("77777777-7777-7777-7777-777777777777");
        String mall = resource("/db/development/mall/R__dev_mall_catalog.sql");
        for (String product : List.of("401", "201", "301", "101")) {
            assertThat(mall).contains("'c2000000-0000-4000-8000-000000000" + product + "'");
        }
    }

    @Test
    void onlyDevMerchantGetsTheLiveHostRoleForItsOwnShop() throws Exception {
        String sql = seed();

        assertThat(count(sql, "INSERT INTO identity_user_role")).isEqualTo(1);
        assertThat(sql).contains("SELECT 'c1000000-0000-4000-8000-0000000000a1', id, 'c1000000-0000-4000-8000-000000000001'\n"
                + "FROM identity_role WHERE role_code = 'LIVE_HOST'");
        assertThat(sql).doesNotContain("PLATFORM_OPERATOR").doesNotContain("crypt(");
        assertThat(resource("/db/development/trade/R__dev_trade_merchant_operator.sql"))
                .contains("'c1000000-0000-4000-8000-0000000000a1',\n    'dev-merchant',");
        assertThat(resource("/db/migration/identity/V006__identity_live_host_role.sql"))
                .contains("VALUES ('LIVE_HOST', '直播主办', 'ORGANIZATION')");
    }

    private static Method method(String name) {
        return Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private String seed() throws Exception {
        return resource(SEED);
    }

    private String resource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
