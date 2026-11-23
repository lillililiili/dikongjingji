package com.lowaltitude.inspection.activity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 运营端“活动与场次”的 SQL（没有数据库测试容器，按现有做法检查文本）：只用绑定参数，已删除的当作不存在，
 * 写都带版本号，场次的改、删先锁住行，删场次前确认没有报名单；迁移 V004 的约束。
 */
class ActivityOpsMapperContractTest {

    @Test
    void everyStatementBindsItsParameters() {
        List<String> statements = Arrays.stream(ActivityOpsMapper.class.getDeclaredMethods())
                .map(ActivityOpsMapperContractTest::sqlOf)
                .filter(sql -> sql != null)
                .toList();
        assertThat(statements).hasSize(18);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void deletedActivitiesNeverShowUpOrChange() throws Exception {
        assertThat(ActivityOpsMapper.LIST_FILTER).contains("a.status <> 'DELETED'")
                .contains("CAST(#{keyword} AS varchar) IS NULL OR a.title ILIKE '%' || #{keyword} || '%'");
        assertThat(sql("find")).contains("a.status <> 'DELETED'");
        for (String method : List.of("update", "changeStatus")) {
            assertThat(sql(method)).contains("AND version = #{version}").contains("AND status <> 'DELETED'");
        }
        assertThat(sql("page")).contains("ORDER BY a.updated_at DESC, a.id DESC")
                .contains("LIMIT #{limit} OFFSET #{offset}");
        assertThat(sql("changeStatus")).contains("coalesce(first_published_at, #{at})");
        // 有报名的判断只看待付款和已确认的报名单。
        assertThat(ActivityOpsMapper.ACTIVITY_COLUMNS).contains("e.status IN ('PENDING_PAYMENT', 'CONFIRMED')");
    }

    @Test
    void waitlistAndTeamSwitchesAreWrittenAndTheActiveQueueIsCounted() throws Exception {
        assertThat(sql("insert")).contains("waitlist_allowed, team_enrollment_allowed")
                .contains("#{d.waitlistAllowed}, #{d.teamEnrollmentAllowed}");
        assertThat(sql("update")).contains("waitlist_allowed = #{d.waitlistAllowed}")
                .contains("team_enrollment_allowed = #{d.teamEnrollmentAllowed}");
        // 列的顺序和 ActivityRow 的字段一致（按位置映射到记录）：has_enrollments 之后是两个开关和排队条数。
        assertThat(ActivityOpsMapper.ACTIVITY_COLUMNS)
                .contains("has_enrollments,\n       a.waitlist_allowed, a.team_enrollment_allowed,")
                .contains("w.status IN ('WAITING', 'OFFERED'))::integer active_waitlists");
    }

    @Test
    void sessionWritesLockCheckVersionsAndKeepEnrollments() throws Exception {
        assertThat(sql("lockSession")).contains("FOR UPDATE OF s").contains("s.activity_id = #{activityId}");
        assertThat(sql("updateSession")).contains("AND version = #{version}")
                .contains("AND enrolled_count <= #{s.capacity}");
        assertThat(sql("changeSaleStatus")).contains("AND version = #{version}");
        assertThat(sql("deleteSession")).contains("AND s.version = #{version}")
                .contains("NOT EXISTS (SELECT 1 FROM activity_enrollment e WHERE e.session_id = s.id)");
        assertThat(sql("insertSession")).contains("'ON_SALE'");
        assertThat(ActivityOpsMapper.SESSION_COLUMNS).contains("enrollment_count");
    }

    @Test
    void migrationAddsTheOpsColumnsAndTablesWithoutData() throws Exception {
        String migration;
        try (var stream = getClass().getResourceAsStream("/db/migration/activity/V004__activity_ops.sql")) {
            assertThat(stream).isNotNull();
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(migration)
                .contains("ADD COLUMN enrollment_notice varchar(2000)")
                .contains("ADD COLUMN first_published_at timestamptz")
                .contains("CREATE TABLE activity_gallery_image (")
                .contains("CHECK (sort_order BETWEEN 1 AND 9)")
                .contains("CHECK (url LIKE 'https://%')")
                .contains("CHECK (sale_status IN ('ON_SALE', 'STOPPED'))")
                .contains("CREATE TABLE activity_ops_change (")
                .contains("CHECK (action IN ('CREATE', 'UPDATE', 'PUBLISH', 'OFFLINE', 'DELETE', 'STOP_SALE', "
                        + "'RESUME_SALE'))")
                .contains("operator_name varchar(80) NOT NULL");
        assertThat(migration.toUpperCase()).doesNotContain("INSERT INTO");
    }

    private static String sqlOf(Method method) {
        if (method.getAnnotation(Select.class) != null) {
            return String.join("\n", method.getAnnotation(Select.class).value());
        }
        if (method.getAnnotation(Update.class) != null) {
            return String.join("\n", method.getAnnotation(Update.class).value());
        }
        if (method.getAnnotation(Insert.class) != null) {
            return String.join("\n", method.getAnnotation(Insert.class).value());
        }
        if (method.getAnnotation(Delete.class) != null) {
            return String.join("\n", method.getAnnotation(Delete.class).value());
        }
        return null;
    }

    private static String sql(String methodName) {
        Method method = Arrays.stream(ActivityOpsMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return sqlOf(method);
    }
}
