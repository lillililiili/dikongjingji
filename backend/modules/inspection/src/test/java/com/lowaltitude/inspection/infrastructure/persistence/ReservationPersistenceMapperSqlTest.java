package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.apache.ibatis.annotations.Insert;
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
    void locks_service_and_counts_only_active_reservations_by_date_and_slot() throws Exception {
        Method lock = ReservationPersistenceMapper.class.getMethod(
                "lockActiveService", java.util.UUID.class, String.class);
        String lockSql = String.join(" ", lock.getAnnotation(
                org.apache.ibatis.annotations.Select.class).value());
        assertThat(lockSql).contains("FOR UPDATE");

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
}
