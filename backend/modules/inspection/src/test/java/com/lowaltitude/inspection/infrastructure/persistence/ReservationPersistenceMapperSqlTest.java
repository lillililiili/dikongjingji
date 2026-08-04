package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

class ReservationPersistenceMapperSqlTest {

    @Test
    void declares_the_type_of_nullable_coordinate_parameters() throws Exception {
        Method insert = ReservationPersistenceMapper.class.getMethod(
                "insertReservation",
                ReservationPersistenceMapper.ReservationRow.class);
        String sql = String.join(" ", insert.getAnnotation(Insert.class).value());

        assertThat(sql)
                .contains("CAST(#{longitude} AS double precision)")
                .contains("CAST(#{latitude} AS double precision)");
    }

    @Test
    void customerOrderProjectionIsScopedAndUsesStableLifecycleMapping() throws Exception {
        Method list = ReservationPersistenceMapper.class.getMethod(
                "findCustomerReservations",
                UUID.class,
                String.class,
                int.class,
                int.class);
        String sql = String.join(" ", list.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("r.customer_id = #{customerId}")
                .contains("WHEN r.status = 'CANCELLED' THEN 'CANCELLED'")
                .contains("WHEN o.status = 'ACCEPTED' THEN 'COMPLETED'")
                .contains("WHEN o.status = 'DELIVERED' THEN 'AWAITING_ACCEPTANCE'")
                .contains("WHEN o.status IN ('ARRANGING', 'IN_PROGRESS') THEN 'IN_PROGRESS'")
                .contains("WHEN p.status = 'PUBLISHED' THEN 'AWAITING_CONFIRMATION'")
                .contains("ELSE 'AWAITING_PLAN'")
                .contains("ORDER BY lifecycle_updated_at DESC, r.id DESC")
                .contains("LIMIT #{limit} OFFSET #{offset}")
                .doesNotContain("contact_mobile_ciphertext")
                .doesNotContain("contact_name_ciphertext");
    }

    @Test
    void locks_service_and_counts_only_active_reservations_by_date_and_slot() throws Exception {
        Method find = ReservationPersistenceMapper.class.getMethod(
                "findActiveService", java.util.UUID.class, String.class);
        String findSql = String.join(" ", find.getAnnotation(
                org.apache.ibatis.annotations.Select.class).value());
        assertThat(findSql)
                .contains("inspection_service_city")
                .contains("coverage.city_code = #{cityCode}")
                .contains("coverage.status = 'ACTIVE'");

        Method lock = ReservationPersistenceMapper.class.getMethod(
                "lockActiveService", java.util.UUID.class, String.class);
        String lockSql = String.join(" ", lock.getAnnotation(
                org.apache.ibatis.annotations.Select.class).value());
        assertThat(lockSql)
                .contains("inspection_service_city")
                .contains("coverage.city_code = #{cityCode}")
                .contains("coverage.status = 'ACTIVE'")
                .contains("FOR UPDATE");

        Method counts = ReservationPersistenceMapper.class.getMethod(
                "findBookedTimeSlotCounts",
                java.util.UUID.class,
                String.class,
                java.time.LocalDate.class,
                java.time.LocalDate.class);
        String countSql = String.join(" ", counts.getAnnotation(
                org.apache.ibatis.annotations.Select.class).value());
        assertThat(countSql)
                .contains("preferred_time_slot_code")
                .contains("status <> 'CANCELLED'")
                .contains("GROUP BY preferred_service_date, preferred_time_slot_code");

        Method insert = ReservationPersistenceMapper.class.getMethod(
                "insertReservation",
                ReservationPersistenceMapper.ReservationRow.class);
        String insertSql = String.join(" ", insert.getAnnotation(Insert.class).value());
        assertThat(insertSql).contains("preferred_time_slot_code");
    }

    @Test
    void cancellationSqlWithdrawsPublishedPlanAndRejectsExistingServiceOrder() throws Exception {
        String withdraw = updateSql("withdrawPublishedPlanForCancellation");
        String cancel = updateSql("cancelReservation");

        assertThat(withdraw)
                .contains("status = 'WITHDRAWN'")
                .contains("status = 'PUBLISHED'")
                .contains("id = #{planId}")
                .contains("version = version + 1");
        assertThat(cancel)
                .contains("status = 'CANCELLED'")
                .contains("cancelled_at = #{cancelledAt}")
                .contains("status IN ('SUBMITTED', 'PLAN_PUBLISHED')")
                .contains("version = #{expectedVersion}")
                .contains("NOT EXISTS")
                .contains("inspection_service_order")
                .contains("status = 'CONFIRMED'");
    }

    private static String updateSql(String methodName) {
        Method method = Arrays.stream(ReservationPersistenceMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return String.join(" ", method.getAnnotation(Update.class).value());
    }
}
