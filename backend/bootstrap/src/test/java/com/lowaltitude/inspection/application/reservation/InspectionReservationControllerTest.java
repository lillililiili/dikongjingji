package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InspectionReservationControllerTest {

    private static final UUID CUSTOMER =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_CUSTOMER =
            UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID PROVIDER =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SERVICE =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID IDEMPOTENCY_KEY =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final String REQUEST_ID = "f04-controller-test";
    private static final Clock BOOKING_CLOCK = Clock.fixed(
            Instant.parse("2026-07-31T01:00:00Z"), ZoneOffset.UTC);

    private InMemoryReservationStore store;
    private InMemoryCustomerReservationQueryStore queryStore;
    private ReservationApplicationService service;
    private CustomerReservationQueryService queryService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryReservationStore();
        service = service(store);
        queryStore = new InMemoryCustomerReservationQueryStore();
        queryService = new CustomerReservationQueryService(queryStore, new ReversibleTestCipher());
        mvc = MockMvcBuilders
                .standaloneSetup(new InspectionReservationController(service, queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void customerCreatesAndReadsOwnReservationUsingRealJwtClaims() throws Exception {
        String id = createReservation(customerJwt(), requestJson("东营市测试路1号"));

        mvc.perform(get("/api/v1/inspection/reservations/{id}", id)
                        .principal(customerJwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.preferredTimeSlotCode").value("MORNING"))
                .andExpect(jsonPath("$.attachments").isArray())
                .andExpect(jsonPath("$.inspectionItems[0].code").value("PIPELINE"))
                .andExpect(jsonPath("$.location.address").value("东营市测试路1号"))
                .andExpect(jsonPath("$.contact.mobile").value("13800138000"));
    }

    @Test
    void providerCanReadOnlyItsOrganizationAndPlatformCanReadAll() throws Exception {
        String id = createReservation(customerJwt(), requestJson("东营市测试路2号"));

        mvc.perform(get("/api/v1/inspection/reservations/{id}", id)
                        .principal(subject(
                                UUID.randomUUID(),
                                Set.of("PROVIDER_OPERATOR"),
                                PROVIDER,
                                Set.of("inspection.reservation.read.assigned"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/inspection/reservations/{id}", id)
                        .principal(subject(
                                UUID.randomUUID(),
                                Set.of("PROVIDER_OPERATOR"),
                                UUID.randomUUID(),
                                Set.of("inspection.reservation.read.assigned"))))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/inspection/reservations/{id}", id)
                        .principal(subject(
                                UUID.randomUUID(),
                                Set.of("PLATFORM_OPERATOR"),
                                UUID.randomUUID(),
                                Set.of("inspection.reservation.read.all"))))
                .andExpect(status().isOk());
    }

    @Test
    void customerCannotProbeAnotherCustomersReservation() throws Exception {
        String id = createReservation(customerJwt(), requestJson("东营市测试路3号"));

        mvc.perform(get("/api/v1/inspection/reservations/{id}", id)
                        .principal(subject(
                                OTHER_CUSTOMER,
                                Set.of("CUSTOMER"),
                                null,
                                Set.of("inspection.reservation.read.own"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    void createRequiresCustomerRoleAndPermissionAndFailsClosedForInvalidPrincipal()
            throws Exception {
        mvc.perform(post("/api/v1/inspection/reservations")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType("application/json")
                        .content(requestJson("东营市测试路4号"))
                        .principal(subject(
                                UUID.randomUUID(),
                                Set.of("PROVIDER_OPERATOR"),
                                PROVIDER,
                                Set.of("inspection.reservation.create"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").value(REQUEST_ID));

        Jwt malformed = Jwt.withTokenValue("test")
                .header("alg", "none")
                .claim("subjectId", "not-a-uuid")
                .claim("organizationId", PROVIDER.toString())
                .claim("roles", List.of("CUSTOMER"))
                .claim("permissions", List.of("inspection.reservation.create"))
                .claim("tokenVersion", 0)
                .build();
        mvc.perform(post("/api/v1/inspection/reservations")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(requestJson("东营市测试路5号"))
                        .principal(new UsernamePasswordAuthenticationToken(malformed, "n/a")))
                .andExpect(status().isForbidden());
    }

    @Test
    void httpIdempotencyReplaysSameRequestAndRejectsDifferentRequest() throws Exception {
        String first = createReservation(customerJwt(), requestJson("东营市测试路6号"));
        String replay = createReservation(customerJwt(), requestJson("东营市测试路6号"));

        assertThat(replay).isEqualTo(first);
        assertThat(store.saveCount()).isEqualTo(1);

        mvc.perform(post("/api/v1/inspection/reservations")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType("application/json")
                        .content(requestJson("东营市不同地址7号"))
                        .principal(customerJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INS_IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.traceId").value(REQUEST_ID));
    }

    @Test
    void customerListsOnlyOwnOrdersWithStableStageCounts() throws Exception {
        queryStore.rows = List.of(stored(
                CustomerReservationStage.AWAITING_PLAN,
                "x:东营市测试路8号",
                null,
                null,
                null));
        queryStore.counts = new CustomerReservationCountSnapshot(1, 1, 0, 0, 0, 0);

        mvc.perform(get("/api/v1/inspection/reservations")
                        .param("page", "1")
                        .param("size", "20")
                        .param("stage", "ALL")
                        .principal(customerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].reservationId").exists())
                .andExpect(jsonPath("$.items[0].stage").value("AWAITING_PLAN"))
                .andExpect(jsonPath("$.items[0].quoteAmountMinor").doesNotExist())
                .andExpect(jsonPath("$.stageCounts.AWAITING_PLAN").value(1))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));

        assertThat(queryStore.customerId).isEqualTo(CUSTOMER);
    }

    @Test
    void customerOrderCollectionRejectsInvalidStageAndMissingPermission() throws Exception {
        mvc.perform(get("/api/v1/inspection/reservations")
                        .param("stage", "CANCELLED")
                        .principal(customerJwt()))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/inspection/reservations")
                        .principal(subject(
                                CUSTOMER,
                                Set.of("CUSTOMER"),
                                null,
                                Set.of())))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerOrderCollectionRejectsPageWithUnrepresentableOffset() throws Exception {
        mvc.perform(get("/api/v1/inspection/reservations")
                        .param("page", Integer.toString(Integer.MAX_VALUE))
                        .param("size", "50")
                        .principal(customerJwt()))
                .andExpect(status().isBadRequest());
    }

    private static StoredCustomerReservationSummary stored(
            CustomerReservationStage stage,
            String locationCiphertext,
            UUID currentPlanId,
            UUID serviceOrderId,
            Long quoteAmountMinor
    ) {
        return new StoredCustomerReservationSummary(
                UUID.randomUUID(),
                "IR202607310001",
                SERVICE,
                "服务端标题",
                "https://cdn.example.com/power-line.jpg",
                "370500",
                locationCiphertext,
                LocalDate.of(2026, 8, 1),
                Instant.parse("2026-07-31T00:00:00Z"),
                stage,
                "SUBMITTED",
                currentPlanId,
                serviceOrderId,
                null,
                quoteAmountMinor);
    }

    @Test
    void returnsConflictWhenTimeSlotCapacityIsFull() throws Exception {
        for (int index = 0; index < 9; index++) {
            mvc.perform(post("/api/v1/inspection/reservations")
                            .header("Idempotency-Key", UUID.randomUUID())
                            .contentType("application/json")
                            .content(requestJson("东营市容量测试路"))
                            .principal(customerJwt()))
                    .andExpect(status().isCreated());
        }

        mvc.perform(post("/api/v1/inspection/reservations")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType("application/json")
                        .content(requestJson("东营市容量测试路"))
                        .principal(customerJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INS_TIME_SLOT_FULL"))
                .andExpect(jsonPath("$.traceId").value(REQUEST_ID));
    }

    @Test
    void returnsConflictWhenTimeSlotDoesNotMeetOneHourLeadTime() throws Exception {
        service = new ReservationApplicationService(
                new InMemoryServiceCatalog(SERVICE, PROVIDER, "服务端标题", null),
                store,
                new ReversibleTestCipher(),
                Clock.fixed(Instant.parse("2026-08-01T09:00:01Z"), ZoneOffset.UTC));
        mvc = MockMvcBuilders
                .standaloneSetup(new InspectionReservationController(service, queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/inspection/reservations")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType("application/json")
                        .content(requestJson(
                                "东营市提前量测试路", "2026-08-01", "EVENING"))
                        .principal(customerJwt()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INS_TIME_SLOT_TOO_SOON"))
                .andExpect(jsonPath("$.message").value("该时段需提前1小时预约，请重新选择"))
                .andExpect(jsonPath("$.traceId").value(REQUEST_ID));
    }

    private String createReservation(
            UsernamePasswordAuthenticationToken principal,
            String requestBody
    ) throws Exception {
        String body = mvc.perform(post("/api/v1/inspection/reservations")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-Request-Id", REQUEST_ID)
                        .contentType("application/json")
                        .content(requestBody)
                        .principal(principal))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.serviceTitle").value("服务端标题"))
                .andExpect(jsonPath("$.preferredTimeSlotCode").value("MORNING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static ReservationApplicationService service(InMemoryReservationStore store) {
        return new ReservationApplicationService(
                new InMemoryServiceCatalog(SERVICE, PROVIDER, "服务端标题", null),
                store,
                new ReversibleTestCipher(),
                BOOKING_CLOCK);
    }

    private static String requestJson(String address) {
        return requestJson(address, "2026-08-01", "MORNING");
    }

    private static String requestJson(
            String address,
            String serviceDate,
            String slotCode
    ) {
        return """
                {
                  "serviceId": "%s",
                  "cityCode": "370500",
                  "location": {
                    "address": "%s",
                    "longitude": 118.5,
                    "latitude": 37.4
                  },
                  "lineLengthMeters": 12,
                  "preferredServiceDate": "%s",
                  "preferredTimeSlotCode": "%s",
                  "inspectionItemCodes": ["PIPELINE"],
                  "contact": {"name": "张三", "mobile": "13800138000"},
                  "agreementVersion": "v1",
                  "agreementAccepted": true
                }
                """.formatted(SERVICE, address, serviceDate, slotCode);
    }

    private static UsernamePasswordAuthenticationToken customerJwt() {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject(CUSTOMER.toString())
                .claim("subjectId", CUSTOMER.toString())
                .claim("organizationId", UUID.randomUUID().toString())
                .claim("roles", List.of("CUSTOMER"))
                .claim("permissions", List.of(
                        "inspection.reservation.create",
                        "inspection.reservation.read.own"))
                .claim("tokenVersion", 0)
                .build();
        return new UsernamePasswordAuthenticationToken(jwt, "n/a");
    }

    private static UsernamePasswordAuthenticationToken subject(
            UUID subjectId,
            Set<String> roles,
            UUID organizationId,
            Set<String> permissions
    ) {
        CurrentSubject subject =
                new CurrentSubject(subjectId, roles, organizationId, permissions, 0);
        return new UsernamePasswordAuthenticationToken(subject, "n/a");
    }
}

final class InMemoryServiceCatalog implements InspectionServiceCatalog {

    private final UUID id;
    private final UUID provider;
    private final String title;
    private final String cover;

    InMemoryServiceCatalog(UUID id, UUID provider, String title, String cover) {
        this.id = id;
        this.provider = provider;
        this.title = title;
        this.cover = cover;
    }

    @Override
    public Optional<InspectionServiceSnapshot> findActive(UUID id, String city) {
        return this.id.equals(id)
                ? Optional.of(
                        new InspectionServiceSnapshot(
                                id,
                                provider,
                                title,
                                cover,
                                city,
                                List.of(new InspectionItemSnapshot(
                                        "PIPELINE",
                                        "管线巡检"))))
                : Optional.empty();
    }
}

final class InMemoryReservationStore implements ReservationStore {

    private final Map<UUID, StoredReservation> values = new HashMap<>();
    private final Map<String, IdempotencyEntry> keys = new HashMap<>();
    private int saveCount;

    @Override
    public Optional<IdempotencyEntry> findIdempotency(UUID actorId, UUID key) {
        return Optional.ofNullable(keys.get(actorId + ":" + key));
    }

    @Override
    public void save(StoredReservation value, IdempotencyEntry entry) {
        values.put(value.id(), value);
        keys.put(entry.actorId() + ":" + entry.key(), entry);
        saveCount++;
    }

    @Override
    public Optional<StoredReservation> findById(UUID id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public void lockActiveService(UUID serviceId, String cityCode) {
        if (serviceId == null || cityCode == null) {
            throw new ReservationValidationException("service is invalid");
        }
    }

    @Override
    public List<BookedTimeSlotCount> findBookedTimeSlotCounts(
            UUID serviceId,
            String cityCode,
            LocalDate startDate,
            LocalDate endDate
    ) {
        return values.values().stream()
                .filter(value -> value.snapshot().serviceId().equals(serviceId))
                .filter(value -> value.cityCode().equals(cityCode))
                .filter(value -> !value.preferredServiceDate().isBefore(startDate))
                .filter(value -> !value.preferredServiceDate().isAfter(endDate))
                .collect(java.util.stream.Collectors.groupingBy(
                        value -> value.preferredServiceDate()
                                + ":"
                                + value.preferredTimeSlotCode(),
                        java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .map(entry -> {
                    String[] key = entry.getKey().split(":", 2);
                    return new BookedTimeSlotCount(
                            LocalDate.parse(key[0]),
                            key[1],
                            entry.getValue().intValue());
                })
                .toList();
    }

    int saveCount() {
        return saveCount;
    }

}

final class InMemoryCustomerReservationQueryStore implements CustomerReservationQueryStore {

    UUID customerId;
    List<StoredCustomerReservationSummary> rows = List.of();
    CustomerReservationCountSnapshot counts =
            new CustomerReservationCountSnapshot(0, 0, 0, 0, 0, 0);

    @Override
    public List<StoredCustomerReservationSummary> findOwn(
            UUID customerId,
            CustomerReservationFilter filter,
            int limit,
            int offset
    ) {
        this.customerId = customerId;
        return rows.stream()
                .filter(row -> filter == CustomerReservationFilter.ALL
                        || row.stage().name().equals(filter.name()))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public CustomerReservationCountSnapshot countOwn(UUID customerId) {
        this.customerId = customerId;
        return counts;
    }
}

final class ReversibleTestCipher implements SensitiveDataCipher {

    @Override
    public String encrypt(String value) {
        return "x:" + value;
    }

    @Override
    public String decrypt(String value) {
        return value.substring(2);
    }
}
