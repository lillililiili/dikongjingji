package com.lowaltitude.inspection.activity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查 SQL 文本：只用绑定参数、只读已发布且有效的数据，
 * 并检查迁移脚本的约束。真实执行由 dev 环境启动和接口联调覆盖。
 */
class ActivityCatalogMapperContractTest {

    @Test
    void everyQueryBindsItsParametersAndNeverInterpolatesText() {
        List<String> statements = statements();

        assertThat(statements).hasSize(6);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void listAndCountShareOneFilterOverPublishedActivitiesOfTheCity() throws Exception {
        String count = sql("countListed");
        String page = sql("findListedPage");

        for (String sql : List.of(count, page)) {
            assertThat(sql)
                    .contains("a.status = 'PUBLISHED'")
                    .contains("a.city_code = #{cityCode}")
                    .contains("#{type} = 'ALL' OR a.type = #{type}")
                    .contains("#{freeOnly} = false OR a.price_mode = 'FREE'")
                    .contains("#{enrollingOnly} = false OR EXISTS")
                    .contains("#{filterByDate} = false OR EXISTS")
                    .contains("upcoming.starts_at > #{now}");
        }
        assertThat(count).contains(ActivityCatalogMapper.LISTED_ACTIVITY_FILTER);
        assertThat(page).contains(ActivityCatalogMapper.LISTED_ACTIVITY_FILTER);
        assertThat(page)
                .contains("ORDER BY next_session.starts_at ASC, a.id ASC")
                .contains("LIMIT #{limit} OFFSET #{offset}");
    }

    @Test
    void cardPrefersTheEarliestSessionThatCanStillBeBooked() throws Exception {
        assertThat(sql("findListedPage")).contains(
                "ORDER BY (s.sale_status = 'ON_SALE' AND s.enrollment_closes_at > #{now}");
    }

    // S3-1：停售的场次不算“还能报名”，也不算“已满”（读成报名截止）。
    @Test
    void stoppedSessionsNeverCountAsOpenOrFull() throws Exception {
        assertThat(ActivityCatalogMapper.LISTED_ACTIVITY_FILTER).contains("open_session.sale_status = 'ON_SALE'");
        assertThat(sql("findListedPage"))
                .contains("open_session.sale_status = 'ON_SALE'")
                .contains("full_session.sale_status = 'ON_SALE'");
        assertThat(sql("findActiveSessions")).contains("s.sale_status = 'STOPPED' sale_stopped");
        assertThat(sql("findGallery")).contains("ORDER BY g.sort_order ASC");
    }

    @Test
    void sessionsAndDatesIgnoreCancelledSessions() throws Exception {
        assertThat(sql("findActiveSessions")).contains("s.status = 'ACTIVE'");
        assertThat(sql("findSessionDates"))
                .contains("s.status = 'ACTIVE'")
                .contains("a.status = 'PUBLISHED'")
                .contains("AT TIME ZONE #{zoneId}");
        assertThat(sql("findPublishedDetail"))
                .contains("a.id = #{activityId}")
                .contains("a.city_code = #{cityCode}")
                .contains("a.status = 'PUBLISHED'");
    }

    @Test
    void migrationCreatesBothTablesWithTheRequiredColumnsAndNoData() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/activity/V001__activity_catalog.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains("CREATE TABLE activity (")
                .contains("CREATE TABLE activity_session (")
                .contains("city_code varchar(16) NOT NULL")
                .contains("venue_location geography(Point, 4326)")
                .contains("price_amount_minor bigint")
                .contains("ck_activity_price_matches_mode")
                .contains("ck_activity_session_seats CHECK (enrolled_count <= capacity)")
                .contains("ck_activity_session_deadline CHECK (enrollment_closes_at <= starts_at)");
        // 两张表都要有状态、乐观锁和创建/更新时间（AGENTS.md §12）。
        assertThat(migration.split("version integer NOT NULL DEFAULT 0", -1)).hasSize(3);
        assertThat(migration.split("created_at timestamptz NOT NULL DEFAULT now\\(\\)", -1)).hasSize(3);
        assertThat(migration.split("updated_at timestamptz NOT NULL DEFAULT now\\(\\)", -1)).hasSize(3);
        assertThat(migration.toUpperCase()).doesNotContain("INSERT INTO");
    }

    private static List<String> statements() {
        return Arrays.stream(ActivityCatalogMapper.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Select.class))
                .filter(select -> select != null)
                .map(select -> String.join("\n", select.value()))
                .toList();
    }

    private static String sql(String methodName) throws Exception {
        Method method = Arrays.stream(ActivityCatalogMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return String.join("\n", method.getAnnotation(Select.class).value());
    }
}
