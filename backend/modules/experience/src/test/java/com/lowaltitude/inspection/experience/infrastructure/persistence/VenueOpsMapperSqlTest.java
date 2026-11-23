package com.lowaltitude.inspection.experience.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/** 运营端“场馆与设备”的 SQL 文本和迁移 V005（按现有做法检查文本；真实执行由 dev 环境启动和接口联调覆盖）。 */
class VenueOpsMapperSqlTest {

    @Test
    void everyStatementBindsParametersAndOptionalFiltersAreTyped() {
        List<String> statements = Arrays.stream(VenueOpsMapper.class.getDeclaredMethods())
                .map(VenueOpsMapperSqlTest::statement)
                .filter(Objects::nonNull)
                .toList();
        assertThat(statements).hasSizeGreaterThan(30);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        for (String name : List.of("countVenues", "findVenues")) {
            assertThat(sql(name))
                    .contains("v.status <> 'DELETED'")
                    .contains("CAST(#{status} AS varchar) IS NULL")
                    .contains("CAST(#{cityCode} AS varchar) IS NULL")
                    .contains("CAST(#{keyword} AS varchar) IS NULL");
        }
        assertThat(sql("findVenues")).contains("LIMIT #{limit} OFFSET #{offset}");
    }

    @Test
    void writesCheckVersionsAndStayInsideTheirVenue() {
        assertThat(sql("updateVenue")).contains("AND version = #{version}").contains("version = version + 1");
        assertThat(sql("updateVenueStatus")).contains("AND status = #{fromStatus}").contains("AND version = #{version}")
                .contains("published_at = COALESCE(published_at, #{publishedAt})");
        for (String name : List.of("updateProject", "updateProjectStatus", "updateDevice", "updateDeviceStatus")) {
            assertThat(sql(name)).as(name).contains("AND venue_id = #{venueId}").contains("AND version = #{version}");
        }
        assertThat(sql("updateSessionStatus")).contains("AND status = #{fromStatus}").contains("AND version = #{version}");
        for (String name : List.of("lockVenue", "lockProject", "lockSession", "lockDevice")) {
            assertThat(sql(name)).as(name).contains("FOR UPDATE OF");
        }
        assertThat(sql("lockSession")).contains("p.venue_id = #{venueId}").contains("s.id = #{sessionId}");
        assertThat(sql("insertVenue")).contains("'DRAFT'").contains("CAST(#{opensAt} AS time)");
    }

    @Test
    void sessionRulesCountTheRightBookings() {
        assertThat(sql("lockSession")).contains("b.status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN')");
        assertThat(sql("findSessionStarts")).contains("status <> 'CANCELLED'");
        assertThat(sql("countUpcomingSessions")).contains("p.status = 'ACTIVE'").contains("s.status = 'ACTIVE'")
                .contains("s.starts_at > #{now}");
        assertThat(sql("findChanges")).contains("ORDER BY changed_at DESC, id DESC").contains("LIMIT #{limit}");
    }

    @Test
    void migrationAddsTheRulesTheStopStatusAndTheChangeLog() throws Exception {
        String migration = Files.readString(Path.of("migrations", "V005__venue_operations.sql"));
        assertThat(migration)
                .contains("ADD COLUMN opens_at time")
                .contains("ADD COLUMN checkin_opens_minutes integer NOT NULL DEFAULT 30")
                .contains("ADD COLUMN late_entry_minutes integer")
                .contains("DROP CONSTRAINT venue_session_status_check")
                .contains("CHECK (status IN ('ACTIVE', 'STOPPED', 'CANCELLED'))")
                .contains("CREATE TABLE venue_ops_change")
                .doesNotContain("CREATE UNIQUE INDEX uq_venue_session");
        // 到馆核销按场馆的检票规则读。
        assertThat(Arrays.stream(VenueBookingMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("lockBookingByCode"))
                .map(VenueOpsMapperSqlTest::statement).findFirst().orElseThrow())
                .contains("v.checkin_opens_minutes").contains("v.late_entry_minutes");
    }

    private static String sql(String name) {
        return Arrays.stream(VenueOpsMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .map(VenueOpsMapperSqlTest::statement)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no statement " + name));
    }

    private static String statement(Method method) {
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
}
