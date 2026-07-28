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
}
