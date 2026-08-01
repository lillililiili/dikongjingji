package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
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
}
