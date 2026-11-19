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
 * H1：发现页的示例图文和专题只在开发环境加载；全部是示例、没有封面；提到的对象和专题挂的活动、场馆都是别的开发数据里真实存在的 ID。
 * 内容表在所有环境迁移。
 */
class DevelopmentContentSeedTest {

    private static final String SEED = "/db/development/content/R__dev_content.sql";

    @Test
    void theSeedLoadsInDevOnlyAfterTheDataItPointsAt() throws Exception {
        Method seed = method("contentDevelopmentDataFlyway");

        assertThat(seed.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(seed.getAnnotation(DependsOn.class).value()).containsExactlyInAnyOrder("contentFlyway",
                "proserviceDevelopmentDataFlyway", "activityDevelopmentDataFlyway", "experienceDevelopmentDataFlyway");
        seed.setAccessible(true);
        Flyway flyway = (Flyway) seed.invoke(new ModuleFlywayConfiguration(), mock(DataSource.class));
        assertThat(flyway.getConfiguration().getTable()).isEqualTo("flyway_history_content_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/content");
        // 发布时间、专题日期相对执行当天：日期占位符让每天第一次启动重跑。
        assertThat(flyway.getConfiguration().getPlaceholders()).containsKey(ModuleFlywayConfiguration.SEED_DATE_PLACEHOLDER);
        assertThat(seed()).contains("${" + ModuleFlywayConfiguration.SEED_DATE_PLACEHOLDER + "}");

        Method migration = method("contentFlyway");
        assertThat(migration.getAnnotation(Profile.class)).isNull();
        assertThat(migration.getAnnotation(DependsOn.class).value()).containsExactly("inspectionFlyway");
    }

    @Test
    void sixSampleArticlesFromTheBoardsAndOneTopicWithoutPictures() throws Exception {
        String sql = seed();

        for (String title : List.of("一台机器狗如何完成园区夜间巡逻", "无人机航测如何三天出图", "光伏电站巡检：热斑是怎么被找出来的",
                "新手买无人机前，先弄清这 4 件事", "给孩子选编程机器人，看这 5 点", "航拍前的检查清单：场地、天气和电量")) {
            assertThat(sql).as(title).contains("'" + title + "'");
        }
        assertThat(count(sql, ", NULL, true, 'PUBLISHED',")).isEqualTo(6);
        assertThat(sql).contains("'黄河口低空体验季', '到馆体验、赛事、研学合集', 'PLATFORM', NULL, '东营城市运营中心', '370500',")
                .contains("current_date - 18, current_date + 11, NULL, true, 'PUBLISHED'")
                .doesNotContain("https://")
                .doesNotContain("_blob")
                .doesNotContain("INSERT INTO identity_");
        // 商家发布的知识都是广告；平台发布的没有机构。
        assertThat(sql).contains("'MERCHANT', 'c1000000-0000-4000-8000-000000000001', '示例机器人商家', true, NULL")
                .contains("'MERCHANT', 'c1000000-0000-4000-8000-000000000004', '示例无人机厂家', true, NULL")
                .contains("'PLATFORM', NULL, '东营城市运营中心', false, NULL");
        // 每篇十块正文：要点表、导语、小标题、段落、小标题、步骤、提示、小标题、条目、文末说明。
        assertThat(count(sql, "'FACTS', NULL)")).isEqualTo(6);
        assertThat(count(sql, "'STEPS', NULL)")).isEqualTo(6);
        assertThat(count(sql, "'NOTICE', '")).isEqualTo(6);
    }

    // 提到的对象和专题条目指向别的开发数据里存在的 ID。
    @Test
    void refsAndTopicItemsPointAtRealDevelopmentData() throws Exception {
        String sql = seed();
        String inspection = resource("/db/development/inspection/R__dev_inspection_catalog.sql");
        String proservice = resource("/db/development/proservice/R__dev_proservice_catalog.sql");
        String mall = resource("/db/development/mall/R__dev_mall_catalog.sql");
        String activity = resource("/db/development/activity/R__dev_activity_catalog.sql");
        String experience = resource("/db/development/experience/R__dev_experience_catalog.sql");

        Matcher refs = Pattern.compile("'(INSPECTION_SERVICE|PRO_SERVICE|PRODUCT|ACTIVITY|VENUE)', '([0-9a-f-]{36})'")
                .matcher(sql);
        int found = 0;
        while (refs.find()) {
            found++;
            String id = refs.group(2);
            String source = switch (refs.group(1)) {
                case "INSPECTION_SERVICE" -> inspection;
                case "PRO_SERVICE" -> proservice;
                case "PRODUCT" -> mall;
                case "ACTIVITY" -> activity;
                default -> experience;
            };
            assertThat(source).as(refs.group(1) + " " + id).contains(id);
        }
        // 12 个关联对象 + 专题的 3 个活动、1 个场馆。
        assertThat(found).isEqualTo(16);
        assertThat(sql).contains("'ARTICLE', 'f3000000-0000-4000-8000-000000000001'");
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
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

    private static Method method(String name) {
        return Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
