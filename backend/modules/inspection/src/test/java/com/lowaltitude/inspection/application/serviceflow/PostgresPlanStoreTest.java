package com.lowaltitude.inspection.application.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.reservation.AesGcmSensitiveDataCipher;
import com.lowaltitude.inspection.infrastructure.persistence.ServiceFlowPersistenceMapper;
import org.junit.jupiter.api.Test;

class PostgresPlanStoreTest {

    @Test
    void decryptsTheSameUtf8CiphertextRepresentationWrittenByReservationStore() {
        ServiceFlowPersistenceMapper mapper = mock(ServiceFlowPersistenceMapper.class);
        AesGcmSensitiveDataCipher cipher = new AesGcmSensitiveDataCipher(
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        UUID reservationId = UUID.randomUUID();
        String encryptedAddress = cipher.encrypt("东营市黄河路88号");
        when(mapper.findReservation(reservationId)).thenReturn(Optional.of(
                new ServiceFlowPersistenceMapper.ReservationRow(
                        reservationId,
                        "IR1",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "SUBMITTED",
                        UUID.randomUUID(),
                        "输电线路无人机巡检",
                        null,
                        "370500",
                        encryptedAddress.getBytes(StandardCharsets.UTF_8),
                        118.5,
                        37.4,
                        18_600L,
                        0)));
        when(mapper.findReservationItems(reservationId)).thenReturn(List.of());

        PlanReservation reservation = new PostgresPlanStore(mapper, cipher)
                .findReservation(reservationId)
                .orElseThrow();

        assertThat(reservation.address()).isEqualTo("东营市黄河路88号");
    }
}
