package com.lowaltitude.inspection.application.reservation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

class InspectionTimeSlotControllerTest {

    private static final UUID CUSTOMER =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SERVICE =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PROVIDER =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Clock BOOKING_CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void authenticatedCustomerReadsAvailableTimeSlots() throws Exception {
        ReservationApplicationService service = new ReservationApplicationService(
                new TimeSlotServiceCatalog(),
                new TimeSlotReservationStore(),
                new TimeSlotCipher(),
                BOOKING_CLOCK);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new InspectionTimeSlotController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        JsonMapper.builder()
                                .addModule(new JavaTimeModule())
                                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();

        mvc.perform(get(
                        "/api/v1/inspection/services/{serviceId}/available-time-slots",
                        SERVICE)
                        .param("cityCode", "370500")
                        .param("startDate", "2026-08-01")
                        .param("days", "1")
                        .principal(customerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].serviceDate").value("2026-08-01"))
                .andExpect(jsonPath("$[0].slots[0].slotCode").value("MORNING"))
                .andExpect(jsonPath("$[0].slots[0].remainingCount").value(9));
    }

    private static UsernamePasswordAuthenticationToken customerJwt() {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject(CUSTOMER.toString())
                .build();
        return new UsernamePasswordAuthenticationToken(jwt, "n/a");
    }

    private static final class TimeSlotServiceCatalog implements InspectionServiceCatalog {

        @Override
        public Optional<InspectionServiceSnapshot> findActive(UUID serviceId, String cityCode) {
            if (!SERVICE.equals(serviceId)) {
                return Optional.empty();
            }
            return Optional.of(new InspectionServiceSnapshot(
                    SERVICE,
                    PROVIDER,
                    "服务端巡检标题",
                    null,
                    cityCode,
                    List.of()));
        }
    }

    private static final class TimeSlotReservationStore implements ReservationStore {

        @Override
        public Optional<IdempotencyEntry> findIdempotency(UUID actorId, UUID key) {
            return Optional.empty();
        }

        @Override
        public void save(StoredReservation reservation, IdempotencyEntry entry) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredReservation> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public void lockActiveService(UUID serviceId, String cityCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BookedTimeSlotCount> findBookedTimeSlotCounts(
                UUID serviceId,
                String cityCode,
                LocalDate startDate,
                LocalDate endDate
        ) {
            return List.of();
        }
    }

    private static final class TimeSlotCipher implements SensitiveDataCipher {

        @Override
        public String encrypt(String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String decrypt(String value) {
            throw new UnsupportedOperationException();
        }
    }
}
