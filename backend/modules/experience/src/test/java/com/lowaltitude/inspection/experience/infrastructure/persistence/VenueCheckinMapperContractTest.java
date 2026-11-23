package com.lowaltitude.inspection.experience.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 没有可用的数据库测试容器，按现有做法检查核销工作台（V006）的 SQL 文本：只用绑定参数；场次只读本机构运营的场馆；
 * 工作台记录的每条读写都以操作员本人（和他的机构）为条件；预约只在“待到馆”时核销或改场次。再检查 V006 的约束。
 */
class VenueCheckinMapperContractTest {

    @Test
    void everyStatementBindsItsParametersAndNeverInterpolatesText() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(VenueCheckinMapper.class.getDeclaredMethods().length);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
    }

    @Test
    void sessionsAreTheOrganizationsOwn() {
        for (String method : List.of("findSessions", "findOwnSession")) {
            assertThat(sql(method)).as(method).contains("v.operator_id = #{organizationId}");
        }
        assertThat(sql("operatesVenues")).contains("operator_id = #{organizationId}");
        assertThat(sql("findSessions"))
                .contains("s.starts_at >= #{from}")
                .contains("s.starts_at < #{to}")
                .contains("b.status IN ('CONFIRMED', 'CHECKED_IN')")
                .contains("b.status = 'CHECKED_IN'");
    }

    @Test
    void recordsAreReadAndChangedOnlyByTheirOperator() {
        for (String method : List.of("findRecords", "findRecord", "lockRecord", "findRecentRejection")) {
            assertThat(sql(method)).as(method)
                    .contains("r.operator_id = #{operatorId}")
                    .contains("r.organization_id = #{organizationId}");
        }
        assertThat(sql("resolveRecord"))
                .contains("operator_id = #{operatorId}")
                .contains("resolution IS NULL")
                .contains("outcome <> 'CHECKED_IN'");
        assertThat(sql("lockRecord")).contains("FOR UPDATE OF r");
        // 非本机构的凭证不给持有人。
        assertThat(sql("findRecords")).contains("CASE WHEN r.outcome = 'OTHER_ORGANIZATION' THEN NULL ELSE (");
        assertThat(sql("findRecords")).contains("LIMIT 500").contains("ORDER BY r.occurred_at DESC");
    }

    @Test
    void bookingsAreLockedAndOnlyAConfirmedOneIsCheckedInOrMoved() {
        assertThat(sql("lockBookingByCode")).contains("b.code = #{code}").contains("FOR UPDATE OF b");
        assertThat(sql("lockBooking")).contains("b.id = #{bookingId}").contains("FOR UPDATE OF b");
        assertThat(sql("lockSession")).contains("FOR UPDATE OF s");
        assertThat(sql("checkIn")).contains("AND status = 'CONFIRMED'").contains("checked_in_by = #{operatorId}");
        assertThat(sql("moveAndCheckIn"))
                .contains("SET session_id = #{sessionId}")
                .contains("status = 'CHECKED_IN'")
                .contains("AND status = 'CONFIRMED'")
                .doesNotContain("code =");
        assertThat(sql("moveParticipants")).contains("WHERE booking_id = #{bookingId}");
        assertThat(sql("countOtherActiveParticipants")).contains("other.booking_id <> #{bookingId}");
    }

    @Test
    void migrationKeepsTheRecordConsistent() throws Exception {
        String migration = migration("/db/migration/experience/V006__venue_checkin_record.sql");

        assertThat(migration)
                .contains("CREATE TABLE venue_checkin_record")
                .contains("method IN ('SCAN', 'MANUAL')")
                .contains("'CHECKED_IN', 'REPEATED', 'EXPIRED', 'OTHER_ORGANIZATION', 'SESSION_MISMATCH', 'CANCELLED'")
                .doesNotContain("'UNPAID'")
                .contains("resolution IN ('RELEASE', 'REJECT', 'BLOCK')")
                .contains("CHECK ((outcome = 'OTHER_ORGANIZATION') = (booking_session_id IS NULL))")
                .contains("CHECK ((outcome = 'REPEATED') = (first_checked_in_at IS NOT NULL))")
                .contains("CHECK (outcome <> 'CHECKED_IN' OR resolution IS NULL)")
                .contains("CHECK ((resolution IS NOT DISTINCT FROM 'RELEASE') = (admitted_session_id IS NOT NULL))")
                .contains("CHECK (resolution IS DISTINCT FROM 'RELEASE' OR identity_checked)")
                .contains("people smallint NOT NULL CHECK (people BETWEEN 1 AND 10)")
                .contains("code_tail char(4) NOT NULL");
        assertThat(migration).doesNotContain("participant_name");
    }

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : VenueCheckinMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            Insert insert = method.getAnnotation(Insert.class);
            Update update = method.getAnnotation(Update.class);
            String[] text = select != null
                    ? select.value()
                    : insert != null ? insert.value() : update != null ? update.value() : null;
            if (text != null) {
                statements.put(method.getName(), String.join("\n", text));
            }
        }
        return statements;
    }

    private static String sql(String methodName) {
        String sql = statements().get(methodName);
        assertThat(sql).as(methodName).isNotNull();
        return sql;
    }

    private String migration(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
