package com.lowaltitude.inspection.experience.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

/**
 * 到馆预约（E10）的 SQL 文本和迁移脚本：只用绑定参数；预约的读写以账号为条件，核销以场馆运营机构为条件，
 * 付款结果以预约和交易订单两个 ID 一起为条件；每一步只改处在前一个状态的行；名额在锁住场次之后增减；
 * 一人一场一张有效预约由部分唯一索引兜底。真实执行由 dev 环境启动和冒烟覆盖。
 */
class VenueBookingMapperContractTest {

    @Test
    void everyMethodCarriesOneStatementThatOnlyBindsParameters() {
        Map<String, String> statements = statements();

        assertThat(statements).hasSize(VenueBookingMapper.class.getDeclaredMethods().length).hasSize(27);
        assertThat(statements.values()).allSatisfy(sql -> assertThat(sql).doesNotContain("${"));
        assertThat(statements).allSatisfy((method, sql) -> {
            if (sql.contains("<foreach")) {
                assertThat(sql).as(method).startsWith("<script>").contains("</script>");
            }
        });
    }

    @Test
    void seatsOnlyMoveAfterTheSessionRowIsLocked() {
        assertThat(normalized(sql("lockSession"))).endsWith("WHERE s.id = #{sessionId} FOR UPDATE OF s");
        assertThat(sql("findSession")).isEqualTo(VenueBookingMapper.SESSION_COLUMNS.strip());
        assertThat(columns(VenueBookingMapper.SESSION_COLUMNS))
                .containsExactlyElementsOf(snakeCase(VenueBookingMapper.SessionRow.class));
        assertThat(normalized(sql("addBooked")))
                .isEqualTo("UPDATE venue_session SET booked_count = booked_count + #{delta}, version = version + 1,"
                        + " updated_at = #{at} WHERE id = #{sessionId}");
    }

    @Test
    void everyBookingReadOrLockIsLimitedToTheAccount() {
        for (String method : List.of("findBooking", "countBookings", "findBookingPage")) {
            assertThat(normalized(sql(method))).as(method).contains("WHERE b.account_id = #{accountId}");
        }
        assertThat(normalized(sql("lockBooking")))
                .endsWith("WHERE b.id = #{bookingId} AND b.account_id = #{accountId} FOR UPDATE");
        assertThat(normalized(sql("findBookingByKey")))
                .contains("WHERE account_id = #{accountId} AND idempotency_key = #{idempotencyKey}");
        assertThat(normalized(sql("findBookedParticipantIds")))
                .contains("WHERE bp.session_id = #{sessionId} AND bp.active AND b.account_id = #{accountId}");
        assertThat(columns(VenueBookingMapper.BOOKING_COLUMNS))
                .containsExactlyElementsOf(snakeCase(VenueBookingMapper.BookingRow.class));
        assertThat(columns(VenueBookingMapper.STATE_COLUMNS))
                .containsExactlyElementsOf(snakeCase(VenueBookingMapper.StateRow.class));
    }

    @Test
    void listAndCountShareOneBucketFilter() {
        assertThat(sql("countBookings")).contains(VenueBookingMapper.BOOKING_BUCKET.strip());
        assertThat(sql("findBookingPage")).contains(VenueBookingMapper.BOOKING_BUCKET.strip());
        assertThat(normalized(VenueBookingMapper.BOOKING_BUCKET))
                .contains("(#{bucket} = 'UPCOMING' AND (b.status = 'PENDING_PAYMENT'"
                        + " OR (b.status = 'CONFIRMED' AND s.ends_at > #{now})))")
                .contains("(#{bucket} = 'FINISHED' AND (b.status = 'CHECKED_IN'"
                        + " OR (b.status = 'CONFIRMED' AND s.ends_at <= #{now})))")
                .contains("(#{bucket} = 'CANCELLED' AND b.status IN ('CANCELLED', 'CLOSED'))");
        assertThat(normalized(sql("findBookingPage"))).endsWith("LIMIT #{limit} OFFSET #{offset}");
    }

    // 付款结果只认预约和它的交易订单两个 ID 一起。
    @Test
    void paymentResultsFindTheBookingByItsTradeOrderToo() {
        assertThat(normalized(sql("findPaidBooking")))
                .endsWith("WHERE b.id = #{bookingId} AND b.trade_order_id = #{tradeOrderId}");
        assertThat(normalized(sql("lockPaidBooking")))
                .endsWith("WHERE b.id = #{bookingId} AND b.trade_order_id = #{tradeOrderId} FOR UPDATE");
    }

    @Test
    void everyStepOnlyMovesRowsInTheStateItExpects() {
        assertThat(where("confirmPayment")).isEqualTo("WHERE id = #{bookingId} AND status = 'PENDING_PAYMENT'");
        assertThat(normalized(sql("confirmPayment"))).contains("SET status = 'CONFIRMED', code = #{code}");
        assertThat(where("closeUnpaid")).isEqualTo("WHERE id = #{bookingId} AND status = 'PENDING_PAYMENT'");
        assertThat(where("restoreClosed")).isEqualTo("WHERE id = #{bookingId} AND status = 'CLOSED'");
        assertThat(normalized(sql("restoreClosed"))).contains("closed_at = NULL");
        assertThat(where("cancelConfirmed")).isEqualTo("WHERE id = #{bookingId} AND status = 'CONFIRMED'");
        assertThat(where("setRefund"))
                .isEqualTo("WHERE id = #{bookingId} AND status = 'CANCELLED' AND refund_id IS NULL");
        // 改期只改仍待到馆、版本也对得上的那一张，换场次、换新码。
        assertThat(normalized(sql("moveBooking")))
                .contains("SET session_id = #{sessionId}, code = #{code}, version = version + 1")
                .endsWith("WHERE id = #{bookingId} AND status = 'CONFIRMED' AND version = #{version}");
        assertThat(where("checkIn")).isEqualTo("WHERE id = #{bookingId} AND status = 'CONFIRMED'");
        assertThat(where("releaseParticipants")).isEqualTo("WHERE booking_id = #{bookingId} AND active");
        assertThat(where("holdParticipants")).isEqualTo("WHERE booking_id = #{bookingId} AND NOT active");
        assertThat(where("moveParticipants")).isEqualTo("WHERE booking_id = #{bookingId}");
        assertThat(normalized(sql("countOtherActiveParticipants")))
                .contains("WHERE other.session_id = #{sessionId} AND other.active"
                        + " AND other.booking_id <> #{bookingId}")
                .contains("WHERE mine.booking_id = #{bookingId}");
    }

    // 核销只找得到本机构运营的场馆的码，并锁住预约单这一行。
    @Test
    void checkInOnlyFindsCodesOfTheOperatorsVenues() {
        assertThat(normalized(sql("lockBookingByCode")))
                .endsWith("WHERE b.code = #{code} AND v.operator_id = #{organizationId} FOR UPDATE OF b");
    }

    @Test
    void theV002MigrationKeepsOneLiveBookingPerPersonAndSessionAndTiesStatesToTheirColumns() throws Exception {
        String migration = normalized(migration("/db/migration/experience/V002__venue_booking.sql"));

        assertThat(migration)
                .contains("CREATE TABLE venue_booking (")
                .contains("CREATE TABLE venue_booking_participant (")
                .contains("CREATE TABLE venue_booking_event (")
                .contains("CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN', 'CANCELLED', 'CLOSED'))")
                .contains("code varchar(24) UNIQUE CHECK (code ~ '^VEN-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$')")
                .contains("people smallint NOT NULL CHECK (people BETWEEN 1 AND 10)")
                .contains("CHECK (amount_minor = unit_amount_minor * people)")
                .contains("(amount_minor > 0 AND trade_order_id IS NOT NULL AND pay_before IS NOT NULL)"
                        + " OR (amount_minor = 0 AND trade_order_id IS NULL AND pay_before IS NULL)")
                .contains("CHECK ((status IN ('PENDING_PAYMENT', 'CLOSED')) = (code IS NULL))")
                .contains("trade_order_id uuid UNIQUE,")
                .contains("UNIQUE (account_id, idempotency_key)")
                .contains("CREATE UNIQUE INDEX uq_venue_booking_participant_active"
                        + " ON venue_booking_participant (session_id, participant_id) WHERE active;")
                .contains("CHECK ( actor_id IS NOT NULL OR event_type IN ('PAYMENT_CONFIRMED', 'PAYMENT_CLOSED',"
                        + " 'PAYMENT_RESTORED') )");
        // 参与人名单（活动模块）、交易订单和退款（交易模块）只存 ID，不加外键。
        assertThat(migration)
                .doesNotContain("REFERENCES activity_participant")
                .doesNotContain("REFERENCES trade_");
        // 不存证件号码和手机号。
        assertThat(migration.toLowerCase()).doesNotContain("phone").doesNotContain("id_card").doesNotContain("mobile");
    }

    // ---- 辅助 ----

    private static Map<String, String> statements() {
        Map<String, String> statements = new LinkedHashMap<>();
        for (Method method : VenueBookingMapper.class.getDeclaredMethods()) {
            Select select = method.getAnnotation(Select.class);
            Insert insert = method.getAnnotation(Insert.class);
            Update update = method.getAnnotation(Update.class);
            String[] text = select != null ? select.value()
                    : insert != null ? insert.value()
                    : update != null ? update.value()
                    : null;
            if (text != null) {
                statements.put(method.getName(), String.join("\n", text).strip());
            }
        }
        return statements;
    }

    private static String sql(String methodName) {
        String sql = statements().get(methodName);
        assertThat(sql).as(methodName).isNotNull();
        return sql;
    }

    private static String where(String methodName) {
        String text = normalized(sql(methodName));
        return text.substring(text.indexOf("WHERE"));
    }

    /** SELECT 和第一个 FROM 之间的列名，取别名（有 AS 名的取名字），去掉表前缀。 */
    private static List<String> columns(String select) {
        // 列里的子查询（机构有没有被挂起上架）只留列名。
        String text = normalized(select).replaceAll("EXISTS \\([^)]*\\) ", "");
        String list = text.substring(text.indexOf("SELECT") + "SELECT".length(), text.indexOf(" FROM "));
        return Arrays.stream(list.split(","))
                .map(String::trim)
                .map(column -> column.contains(" ") ? column.substring(column.lastIndexOf(' ') + 1) : column)
                .map(column -> column.contains(".") ? column.substring(column.indexOf('.') + 1) : column)
                .toList();
    }

    private static List<String> snakeCase(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName().replaceAll("([A-Z])", "_$1").toLowerCase())
                .toList();
    }

    private String migration(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 去掉注释、把连续空白并成一个空格。 */
    private static String normalized(String sql) {
        return Arrays.stream(sql.split("\n"))
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining(" "))
                .replaceAll("\\s+", " ")
                .trim();
    }
}
