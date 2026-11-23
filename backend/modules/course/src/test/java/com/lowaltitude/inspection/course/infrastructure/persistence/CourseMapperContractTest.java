package com.lowaltitude.inspection.course.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * S5-5 的 SQL 文本和 V001 迁移：只用绑定参数、客户的读写按账号限定、交易回调对上交易订单、条件更新、加锁，
 * 以及迁移里的约束。规则由 CourseServiceTest 验证，真实库上由冒烟脚本 course.cjs 验证。
 */
class CourseMapperContractTest {

    @Test
    void everyStatementUsesBoundParametersOnly() {
        List<String> statements = Stream.of(CourseMapper.class, CourseOpsMapper.class)
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .filter(method -> !method.isSynthetic() && !method.isDefault())
                .map(CourseMapperContractTest::sql)
                .toList();

        assertThat(statements).hasSizeGreaterThan(40);
        assertThat(statements).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void customerReadsAreScopedAndChangesAreConditional() throws Exception {
        for (String name : List.of("findEnrollment", "lockEnrollment", "findLiveEnrollments", "findByKey",
                "findActiveCourseIds")) {
            assertThat(sql(method(CourseMapper.class, name))).contains("account_id = #{accountId}");
        }
        assertThat(sql(method(CourseMapper.class, "countEnrollments"))).contains("e.account_id = #{accountId}");
        assertThat(sql(method(CourseMapper.class, "lockPaidEnrollment")))
                .contains("e.id = #{enrollmentId} AND e.trade_order_id = #{tradeOrderId} FOR UPDATE");
        for (String name : List.of("lockCourse", "lockEnrollment", "lockPaidEnrollment")) {
            assertThat(sql(method(CourseMapper.class, name))).endsWith("FOR UPDATE");
        }
        assertThat(sql(method(CourseMapper.class, "activate"))).contains("status IN ('PENDING_PAYMENT', 'CLOSED')");
        assertThat(sql(method(CourseMapper.class, "markRefunded")))
                .contains("status = 'ACTIVE'").contains("source = 'PURCHASE'").contains("started_at IS NULL");
        assertThat(sql(method(CourseMapper.class, "markStarted"))).contains("started_at IS NULL");
        assertThat(sql(method(CourseMapper.class, "markLessonCompleted"))).contains("completed_at IS NULL");
        assertThat(sql(method(CourseMapper.class, "insertBundle"))).contains("ON CONFLICT (bundle_order_id, course_id) DO NOTHING");
        for (String name : List.of("updateCourse", "setStatus", "bumpVersion")) {
            assertThat(sql(method(CourseOpsMapper.class, name))).contains("version = #{version}");
        }
    }

    @Test
    void theMigrationKeepsOneOpenEnrollmentPerCourseAndOnlyHttpsMedia() throws IOException {
        String sql = migration();

        assertThat(sql)
                .contains("CREATE UNIQUE INDEX uq_course_enrollment_active ON course_enrollment (account_id, course_id) WHERE status = 'ACTIVE';")
                .contains("UNIQUE (bundle_order_id, course_id)")
                .contains("(source = 'PURCHASE') = (trade_order_id IS NOT NULL)")
                .contains("(delivery_mode = 'OFFLINE') = (activity_id IS NOT NULL)")
                .contains("video_url varchar(500) CHECK (video_url LIKE 'https://%')")
                .contains("cover_url varchar(500) CHECK (cover_url LIKE 'https://%')")
                .contains("price_amount_minor bigint CHECK (price_amount_minor BETWEEN 1 AND 10000000)");
        // 只存别的模块的 ID：活动、商品、体验项目、交易订单都不加外键。
        assertThat(sql).doesNotContain("REFERENCES activity").doesNotContain("REFERENCES mall_")
                .doesNotContain("REFERENCES trade_").doesNotContain("REFERENCES experience_");
    }

    private static String migration() throws IOException {
        try (InputStream stream = CourseMapperContractTest.class.getResourceAsStream("/db/migration/course/V001__course.sql")) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        }
    }

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods()).filter(method -> method.getName().equals(name)).findFirst()
                .orElseThrow();
    }

    private static String sql(Method method) {
        String[] value;
        if (method.isAnnotationPresent(Select.class)) {
            value = method.getAnnotation(Select.class).value();
        } else if (method.isAnnotationPresent(Insert.class)) {
            value = method.getAnnotation(Insert.class).value();
        } else if (method.isAnnotationPresent(Update.class)) {
            value = method.getAnnotation(Update.class).value();
        } else if (method.isAnnotationPresent(Delete.class)) {
            value = method.getAnnotation(Delete.class).value();
        } else {
            throw new AssertionError(method.getName() + " has no SQL");
        }
        return String.join(" ", value).replaceAll("\\s+", " ").trim();
    }
}
