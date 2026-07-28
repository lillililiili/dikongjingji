package com.lowaltitude.inspection.application.serviceflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InspectionPlanControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-28T02:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID RESERVATION = UUID.fromString("40000000-0000-4000-8000-000000000001");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PlanApplicationService service = new PlanApplicationService(
                new ControllerPlanStore(reservation()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders
                .standaloneSetup(new InspectionPlanController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void providerPublishesCustomerReadsAndConfirmsRealDto() throws Exception {
        String publishBody = mvc.perform(post(
                                "/api/v1/provider/inspection/reservations/{reservationId}/plans",
                                RESERVATION)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .header("X-Request-Id", "f05-publish")
                        .contentType("application/json")
                        .content(publishJson())
                        .principal(provider(PROVIDER)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.planItems[0].lineAmountMinor").value(2400))
                .andExpect(jsonPath("$.deliverables.length()").value(4))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String planId = JsonPath.read(publishBody, "$.id");

        mvc.perform(get("/api/v1/inspection/plans/{planId}", planId)
                        .principal(customer()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.totalAmountMinor").value(2400));

        mvc.perform(post("/api/v1/inspection/plans/{planId}/confirm", planId)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .header("X-Request-Id", "f05-confirm")
                        .contentType("application/json")
                        .content("""
                                {
                                  "agreementVersion": "inspection-plan-v1",
                                  "agreementAccepted": true
                                }
                                """)
                        .principal(customer()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("ARRANGING"))
                .andExpect(jsonPath("$.serviceSnapshot.location.address")
                        .value("东营市黄河路88号"))
                .andExpect(jsonPath("$.assignments").isArray())
                .andExpect(jsonPath("$.progress").isArray());
    }

    @Test
    void publishHidesAnotherProvidersReservationAndConfirmRequiresCustomer() throws Exception {
        mvc.perform(post(
                                "/api/v1/provider/inspection/reservations/{reservationId}/plans",
                                RESERVATION)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content(publishJson())
                        .principal(provider(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));

        mvc.perform(post("/api/v1/inspection/plans/{planId}/confirm", UUID.randomUUID())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("""
                                {
                                  "agreementVersion": "inspection-plan-v1",
                                  "agreementAccepted": true
                                }
                                """)
                        .principal(provider(PROVIDER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
    }

    @Test
    void invalidIfMatchFailsBeforeAnyStateChange() throws Exception {
        mvc.perform(post(
                                "/api/v1/provider/inspection/reservations/{reservationId}/plans",
                                RESERVATION)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "W/\"0\"")
                        .contentType("application/json")
                        .content(publishJson())
                        .principal(provider(PROVIDER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    private static String publishJson() {
        return """
                {
                  "providerId": "%s",
                  "validUntil": "2026-07-29T02:00:00Z",
                  "serviceDate": "2026-08-02",
                  "summary": "无人机精细化巡检方案",
                  "planItems": [
                    {
                      "code": "POWER_LINE",
                      "name": "线路本体巡检",
                      "quantity": 24,
                      "unit": "基",
                      "unitPriceMinor": 100
                    }
                  ],
                  "deliverables": [
                    {"code": "HD_MEDIA", "name": "高清巡检影像"},
                    {"code": "DEFECT_LIST", "name": "缺陷清单"},
                    {"code": "POINT_MARKS", "name": "点位标注"},
                    {"code": "REPORT", "name": "巡检报告"}
                  ],
                  "currency": "CNY"
                }
                """.formatted(PROVIDER);
    }

    private static PlanReservation reservation() {
        return new PlanReservation(
                RESERVATION,
                "IR202607280001",
                CUSTOMER,
                PROVIDER,
                ReservationPlanStatus.SUBMITTED,
                UUID.fromString("70000000-0000-4000-8000-000000000001"),
                "输电线路无人机巡检",
                "/static/images/inspection/inspection-hero-v1.png",
                "370500",
                "东营市黄河路88号",
                118.5,
                37.4,
                18_600L,
                List.of(new CodeLabel("POWER_LINE", "线路本体巡检")),
                0);
    }

    private static UsernamePasswordAuthenticationToken provider(UUID organizationId) {
        return principal(new CurrentSubject(
                UUID.fromString("30000000-0000-4000-8000-000000000002"),
                Set.of("PROVIDER_OPERATOR"),
                organizationId,
                Set.of("inspection.plan.publish.assigned"),
                0));
    }

    private static UsernamePasswordAuthenticationToken customer() {
        return principal(new CurrentSubject(
                CUSTOMER,
                Set.of("CUSTOMER"),
                UUID.randomUUID(),
                Set.of("inspection.plan.read.own", "inspection.plan.confirm.own"),
                0));
    }

    private static UsernamePasswordAuthenticationToken principal(CurrentSubject subject) {
        return new UsernamePasswordAuthenticationToken(subject, "n/a");
    }
}

final class ControllerPlanStore implements PlanStore {

    private PlanReservation reservation;
    private final Map<UUID, StoredServicePlan> plans = new HashMap<>();
    private final Map<UUID, StoredServiceOrder> orders = new HashMap<>();
    private final Map<String, PlanIdempotencyEntry> idempotency = new HashMap<>();

    ControllerPlanStore(PlanReservation reservation) {
        this.reservation = reservation;
    }

    @Override
    public Optional<PlanReservation> findReservation(UUID reservationId) {
        return reservation.id().equals(reservationId) ? Optional.of(reservation) : Optional.empty();
    }

    @Override
    public Optional<StoredServicePlan> findCurrentPlan(UUID reservationId) {
        return plans.values().stream()
                .filter(plan -> plan.reservation().id().equals(reservationId))
                .filter(plan -> plan.status() == PlanStatus.PUBLISHED
                        || plan.status() == PlanStatus.CONFIRMED)
                .findFirst();
    }

    @Override
    public Optional<StoredServicePlan> findPlan(UUID planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    @Override
    public Optional<StoredServiceOrder> findOrder(UUID orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public Optional<PlanIdempotencyEntry> findIdempotency(
            UUID actorId,
            String operation,
            UUID key
    ) {
        return Optional.ofNullable(idempotency.get(key(actorId, operation, key)));
    }

    @Override
    public int nextPlanVersion(UUID reservationId) {
        return plans.size() + 1;
    }

    @Override
    public PlanWriteResult publish(
            PlanReservation expectedReservation,
            StoredServicePlan plan,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        plans.put(plan.id(), plan);
        idempotency.put(key(entry.actorId(), entry.operation(), entry.key()), entry);
        reservation = reservation.withStatusAndVersion(
                ReservationPlanStatus.PLAN_PUBLISHED,
                reservation.version() + 1);
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult confirm(
            StoredServicePlan expectedPlan,
            StoredServiceOrder order,
            PlanIdempotencyEntry entry,
            String agreementVersion,
            CurrentSubject actor,
            String requestId
    ) {
        plans.put(
                expectedPlan.id(),
                expectedPlan.confirmed(order.createdAt(), order.id()));
        orders.put(order.id(), order);
        idempotency.put(key(entry.actorId(), entry.operation(), entry.key()), entry);
        return PlanWriteResult.SAVED;
    }

    private static String key(UUID actorId, String operation, UUID key) {
        return actorId + ":" + operation + ":" + key;
    }
}
