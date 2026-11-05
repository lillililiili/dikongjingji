package com.lowaltitude.inspection.application.serviceflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InspectionOrderControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-28T03:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID VISIT = UUID.fromString("61000000-0000-4000-8000-000000000001");
    private static final String VISIT_PATH =
            "/api/v1/provider/inspection/service-orders/{serviceOrderId}/visits/{visitId}";

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        OrderApplicationService service = new OrderApplicationService(
                new ControllerOrderStore(order()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders
                .standaloneSetup(new InspectionOrderController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void providerArrangesAndStartsWhileCustomerReadsTypedDto() throws Exception {
        mvc.perform(put(
                                "/api/v1/provider/inspection/service-orders/{serviceOrderId}/arrangement",
                                ORDER)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("""
                                {
                                  "assignments": [
                                    {
                                      "type": "TEAM_MEMBER",
                                      "resourceId": "70000000-0000-4000-8000-000000000001",
                                      "displayName": "Dongying team",
                                      "roleLabel": "Lead",
                                      "contactMobile": "13812346621"
                                    },
                                    {
                                      "type": "DEVICE",
                                      "resourceId": "70000000-0000-4000-8000-000000000002",
                                      "displayName": "M350 RTK"
                                    }
                                  ]
                                }
                                """)
                        .principal(provider(Set.of("inspection.order.arrange.assigned"))))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.assignments.length()").value(2))
                .andExpect(jsonPath("$.assignments[0].contactMobileMasked")
                        .value("138****6621"))
                .andExpect(jsonPath("$.assignments[0].contactMobile").doesNotExist())
                .andExpect(jsonPath("$.progress[0].eventCode")
                        .value("ARRANGEMENT_READY"));

        mvc.perform(put(VISIT_PATH + "/schedule", ORDER, VISIT)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("""
                                {"plannedStartAt": "2026-08-02T01:00:00Z"}
                                """)
                        .principal(provider(Set.of("inspection.order.arrange.assigned"))))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.plannedStartAt").exists());

        mvc.perform(post(VISIT_PATH + "/progress-events", ORDER, VISIT)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode": "SERVICE_STARTED",
                                  "occurredAt": "2026-07-28T03:01:00Z",
                                  "message": "Inspection started"
                                }
                                """)
                        .principal(provider(Set.of("inspection.order.progress.assigned"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.progress[0].eventCode").value("SERVICE_STARTED"));

        mvc.perform(get("/api/v1/inspection/service-orders/{serviceOrderId}", ORDER)
                        .principal(customer(CUSTOMER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceOrderNo").value("FW202607280001"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.progress.length()").value(1))
                .andExpect(jsonPath("$.visits[0].status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.visits[0].progress.length()").value(1))
                .andExpect(jsonPath("$.paymentMode").value("NONE"));
    }

    @Test
    void outOfOrderStartAndWrongVisitStateMapToStableErrors() throws Exception {
        mvc.perform(post(VISIT_PATH + "/progress-events", ORDER, VISIT)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventCode": "SERVICE_STARTED",
                                  "occurredAt": "2026-07-28T03:01:00Z",
                                  "message": "Not scheduled yet"
                                }
                                """)
                        .principal(provider(Set.of("inspection.order.progress.assigned"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INS_VISIT_STATE_INVALID"));

        mvc.perform(put(VISIT_PATH + "/schedule", ORDER, UUID.randomUUID())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("""
                                {"plannedStartAt": "2026-08-02T01:00:00Z"}
                                """)
                        .principal(provider(Set.of("inspection.order.arrange.assigned"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    void hidesOrderAcrossCustomersAndProviders() throws Exception {
        mvc.perform(get("/api/v1/inspection/service-orders/{serviceOrderId}", ORDER)
                        .principal(customer(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));

        mvc.perform(put(
                                "/api/v1/provider/inspection/service-orders/{serviceOrderId}/arrangement",
                                ORDER)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"0\"")
                        .contentType("application/json")
                        .content("""
                                {
                                  "assignments": [
                                    {
                                      "type": "TEAM_MEMBER",
                                      "resourceId": "70000000-0000-4000-8000-000000000001",
                                      "displayName": "Other team"
                                    },
                                    {
                                      "type": "DEVICE",
                                      "resourceId": "70000000-0000-4000-8000-000000000002",
                                      "displayName": "Other device"
                                    }
                                  ]
                                }
                                """)
                        .principal(providerForOrganization(
                                UUID.randomUUID(),
                                Set.of("inspection.order.arrange.assigned"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    private static StoredServiceOrder order() {
        return new StoredServiceOrder(
                ORDER,
                "FW202607280001",
                UUID.randomUUID(),
                UUID.randomUUID(),
                CUSTOMER,
                PROVIDER,
                PaymentMode.NONE,
                ServiceOrderStatus.ARRANGING,
                new ServiceSnapshot(
                        UUID.randomUUID(),
                        "Power line inspection",
                        null,
                        "370500",
                        "Huanghe Road 88",
                        118.5,
                        37.4,
                        18_600L,
                        LocalDate.of(2026, 8, 2),
                        List.of(new CodeLabel("POWER_LINE", "Line inspection"))),
                List.of(StoredServiceVisit.planned(
                        VISIT,
                        ORDER,
                        new PlanVisit(1, LocalDate.of(2026, 8, 2)))),
                List.of(),
                NOW.minusSeconds(3_600),
                0);
    }

    private static UsernamePasswordAuthenticationToken customer(UUID customerId) {
        return principal(new CurrentSubject(
                customerId,
                Set.of("CUSTOMER"),
                UUID.randomUUID(),
                Set.of("inspection.order.read.own"),
                0));
    }

    private static UsernamePasswordAuthenticationToken provider(Set<String> permissions) {
        return providerForOrganization(PROVIDER, permissions);
    }

    private static UsernamePasswordAuthenticationToken providerForOrganization(
            UUID providerId,
            Set<String> permissions
    ) {
        return principal(new CurrentSubject(
                UUID.fromString("30000000-0000-4000-8000-000000000002"),
                Set.of("PROVIDER_OPERATOR"),
                providerId,
                permissions,
                0));
    }

    private static UsernamePasswordAuthenticationToken principal(CurrentSubject subject) {
        return new UsernamePasswordAuthenticationToken(subject, "n/a");
    }
}

final class ControllerOrderStore implements PlanStore {

    private StoredServiceOrder order;
    private final Map<String, PlanIdempotencyEntry> idempotency = new HashMap<>();

    ControllerOrderStore(StoredServiceOrder order) {
        this.order = order;
    }

    @Override
    public Optional<StoredServiceOrder> findOrder(UUID orderId) {
        return order.id().equals(orderId) ? Optional.of(order) : Optional.empty();
    }

    @Override
    public Optional<PlanIdempotencyEntry> findIdempotency(
            UUID actorId,
            String operation,
            UUID key
    ) {
        return Optional.ofNullable(idempotency.get(actorId + ":" + operation + ":" + key));
    }

    @Override
    public Optional<StoredServiceVisit> findVisit(UUID visitId) {
        return order.visit(visitId);
    }

    @Override
    public PlanWriteResult arrange(
            StoredServiceOrder expectedOrder,
            StoredServiceOrder arrangedOrder,
            List<AssignmentWrite> assignments,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        order = arrangedOrder;
        idempotency.put(entry.actorId() + ":" + entry.operation() + ":" + entry.key(), entry);
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult schedule(
            StoredServiceOrder expectedOrder,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit scheduledVisit,
            PlanIdempotencyEntry entry,
            Instant at,
            CurrentSubject actor,
            String requestId
    ) {
        replaceVisit(scheduledVisit, order.status());
        idempotency.put(entry.actorId() + ":" + entry.operation() + ":" + entry.key(), entry);
        return PlanWriteResult.SAVED;
    }

    @Override
    public PlanWriteResult appendProgress(
            StoredServiceOrder expectedOrder,
            ServiceOrderStatus nextOrderStatus,
            StoredServiceVisit expectedVisit,
            StoredServiceVisit updatedVisit,
            PlanIdempotencyEntry entry,
            CurrentSubject actor,
            String requestId
    ) {
        replaceVisit(updatedVisit, nextOrderStatus);
        idempotency.put(entry.actorId() + ":" + entry.operation() + ":" + entry.key(), entry);
        return PlanWriteResult.SAVED;
    }

    private void replaceVisit(StoredServiceVisit updated, ServiceOrderStatus status) {
        order = new StoredServiceOrder(
                order.id(),
                order.serviceOrderNo(),
                order.reservationId(),
                order.planId(),
                order.customerId(),
                order.providerId(),
                order.paymentMode(),
                status,
                order.serviceSnapshot(),
                order.assignments(),
                order.progress(),
                ServiceOrderProgression.replace(order.visits(), updated),
                order.paymentNodes(),
                order.createdAt(),
                order.deliveredAt(),
                order.acceptedAt(),
                order.version());
    }

    @Override public Optional<PlanReservation> findReservation(UUID id) { return Optional.empty(); }
    @Override public Optional<StoredServicePlan> findPlan(UUID id) { return Optional.empty(); }
    @Override public int nextPlanVersion(UUID id) { return 1; }
    @Override public PlanWriteResult publish(PlanReservation reservation, StoredServicePlan plan, PlanIdempotencyEntry entry, CurrentSubject actor, String requestId) { return PlanWriteResult.STATE_CONFLICT; }
    @Override public PlanWriteResult confirm(StoredServicePlan plan, StoredServiceOrder order, PlanIdempotencyEntry entry, String agreementVersion, CurrentSubject actor, String requestId) { return PlanWriteResult.STATE_CONFLICT; }
}
