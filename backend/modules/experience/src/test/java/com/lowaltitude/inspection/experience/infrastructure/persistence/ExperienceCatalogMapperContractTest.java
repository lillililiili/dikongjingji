package com.lowaltitude.inspection.experience.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/** 按现有做法检查 SQL 文本和迁移脚本；真实执行由 dev 环境启动和接口联调覆盖。 */
class ExperienceCatalogMapperContractTest {

    @Test
    void everyQueryBindsItsParametersAndNeverInterpolatesText() {
        List<String> statements = Arrays.stream(ExperienceCatalogMapper.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Select.class))
                .filter(select -> select != null)
                .map(select -> String.join("\n", select.value()))
                .toList();

        assertThat(statements).hasSize(14);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void listsOnlyShowPublishedOrActiveRows() throws Exception {
        assertThat(sql("countExperienceItems")).contains("status = 'PUBLISHED'");
        assertThat(sql("findExperienceItem")).contains("id = #{experienceId}").contains("status = 'PUBLISHED'");
        assertThat(sql("findExperienceItems"))
                .contains("status = 'PUBLISHED'")
                .contains("#{type} = 'ALL' OR type = #{type}")
                .contains("LIMIT #{limit} OFFSET #{offset}");
        for (String name : List.of("countVenues", "findVenues")) {
            assertThat(sql(name)).contains("status = 'PUBLISHED'").contains("city_code = #{cityCode}");
        }
        assertThat(sql("findVenue"))
                .contains("v.id = #{venueId}")
                .contains("v.city_code = #{cityCode}")
                .contains("v.status = 'PUBLISHED'");
        for (String name : List.of(
                "findInfoItems", "findProjects", "countActiveProject", "findSessions", "countDevices", "findDevices")) {
            assertThat(sql(name)).contains("status = 'ACTIVE'");
        }
    }

    @Test
    void openSessionsTodayCountsOnlyFutureSessionsWithSeats() throws Exception {
        assertThat(sql("findVenues"))
                .contains("s.starts_at > #{now}")
                .contains("s.starts_at < #{endOfToday}")
                .contains("s.booked_count < s.capacity")
                .contains("p.status = 'ACTIVE'")
                .contains("s.status = 'ACTIVE'");
    }

    @Test
    void aProjectIsOnlyAcceptedInsideItsOwnVenue() throws Exception {
        assertThat(sql("countActiveProject"))
                .contains("id = #{projectId}")
                .contains("venue_id = #{venueId}");
    }

    // R6-2：商品能在哪体验。线上体验只给已发布的；场馆只给这个城市已发布的、设备启用的，一个场馆一行；都有上限。
    @Test
    void productExperiencesOnlyShowPublishedItemsAndTheCitysVenues() throws Exception {
        assertThat(sql("findProductExperiences"))
                .contains("WHERE product_id = #{productId}")
                .contains("status = 'PUBLISHED'")
                .contains("ORDER BY sort_order ASC, id ASC")
                .contains("LIMIT #{limit}");
        assertThat(sql("findProductVenues"))
                .contains("d.product_id = #{productId}")
                .contains("d.status = 'ACTIVE'")
                .contains("v.status = 'PUBLISHED'")
                .contains("v.city_code = #{cityCode}")
                .contains("DISTINCT ON (v.id)")
                .contains("ORDER BY shown.venue_sort ASC, shown.venue_id ASC")
                .contains("LIMIT #{limit}")
                // 不读商城的表。
                .doesNotContain("mall_");
        assertThat(sql("findProductExperiences")).doesNotContain("mall_");
    }

    @Test
    void theV003MigrationOnlyAddsTheProductColumnsWithoutReferencingTheMall() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/experience/V003__experience_product_links.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains("ALTER TABLE experience_item ADD COLUMN product_id uuid;")
                .contains("ALTER TABLE venue_device ADD COLUMN product_id uuid;");
        String upper = migration.toUpperCase();
        assertThat(upper).doesNotContain("REFERENCES").doesNotContain("INSERT INTO").doesNotContain("DROP ");
    }

    @Test
    void migrationCreatesSixTablesWithTheRequiredColumnsAndNoData() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/experience/V001__experience_catalog.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (String table : List.of(
                "experience_item", "venue", "venue_info_item", "venue_project", "venue_session", "venue_device")) {
            assertThat(migration).contains("CREATE TABLE " + table + " (");
        }
        assertThat(migration)
                .contains("city_code varchar(16) NOT NULL")
                .contains("location geography(Point, 4326)")
                .contains("price_amount_minor bigint NOT NULL")
                .contains("ck_venue_session_seats CHECK (booked_count <= capacity)");
        // 六张表都要有状态、乐观锁和创建/更新时间（AGENTS.md §12）。
        assertThat(migration.split("version integer NOT NULL DEFAULT 0", -1)).hasSize(7);
        assertThat(migration.split("created_at timestamptz NOT NULL DEFAULT now\\(\\)", -1)).hasSize(7);
        assertThat(migration.split("updated_at timestamptz NOT NULL DEFAULT now\\(\\)", -1)).hasSize(7);
        assertThat(migration.split("\n    status varchar", -1)).hasSize(7);
        assertThat(migration.toUpperCase()).doesNotContain("INSERT INTO");
    }

    private static String sql(String methodName) throws Exception {
        Method method = Arrays.stream(ExperienceCatalogMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return String.join("\n", method.getAnnotation(Select.class).value());
    }
}
