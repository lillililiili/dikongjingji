package com.lowaltitude.inspection.experience.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.experience.application.VenueBookingException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 到馆预约控制器（E10）：从令牌里取账号和角色，把请求转成服务的参数，把结果、ETag 和业务拒绝转成契约里的状态码。
 * 没带令牌时的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里对七个接口逐个检查。
 */
class VenueBookingControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ORGANIZATION = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID OPERATOR = UUID.fromString("a1000000-0000-4000-8000-0000000000a4");
    private static final UUID OPERATOR_ORG = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID SESSION = UUID.fromString("b5000000-0000-4000-8000-000000000001");
    private static final UUID LATER_SESSION = UUID.fromString("b5000000-0000-4000-8000-000000000002");
    private static final UUID BOOKING = UUID.fromString("b6000000-0000-4000-8000-000000000001");
    private static final UUID SELF = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final UUID CHILD = UUID.fromString("c0000000-0000-4000-8000-000000000002");
    private static final UUID ORDER = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2026-11-21T02:00:00Z");
    private static final String CODE = "VEN-7K2M-9QXD-4HTP";

    /** 每一种业务拒绝对外的状态码和错误码（GlobalExceptionHandler 与契约 1.1.0）。 */
    private static final Map<Error, Rejection> REJECTIONS = Map.ofEntries(
            Map.entry(Error.NOT_FOUND, new Rejection(404, "COMMON_NOT_FOUND")),
            Map.entry(Error.FORBIDDEN, new Rejection(403, "COMMON_FORBIDDEN")),
            Map.entry(Error.IDEMPOTENCY_CONFLICT, new Rejection(409, "VENUE_IDEMPOTENCY_KEY_REUSED")),
            Map.entry(Error.SESSION_NOT_OPEN, new Rejection(409, "VENUE_SESSION_NOT_OPEN")),
            Map.entry(Error.PARTICIPANT_NOT_ELIGIBLE, new Rejection(422, "VENUE_PARTICIPANT_NOT_ELIGIBLE")),
            Map.entry(Error.PARTICIPANT_ALREADY_BOOKED, new Rejection(409, "VENUE_PARTICIPANT_ALREADY_BOOKED")),
            Map.entry(Error.SEATS_NOT_ENOUGH, new Rejection(409, "VENUE_SEATS_NOT_ENOUGH")),
            Map.entry(Error.AMOUNT_CHANGED, new Rejection(409, "VENUE_AMOUNT_CHANGED")),
            Map.entry(Error.PAYMENT_UNAVAILABLE, new Rejection(409, "VENUE_PAYMENT_UNAVAILABLE")),
            Map.entry(Error.VERSION_CONFLICT, new Rejection(412, "VENUE_VERSION_CONFLICT")),
            Map.entry(Error.RESCHEDULE_NOT_ALLOWED, new Rejection(409, "VENUE_RESCHEDULE_NOT_ALLOWED")),
            Map.entry(Error.CANCEL_NOT_ALLOWED, new Rejection(409, "VENUE_CANCEL_NOT_ALLOWED")),
            Map.entry(Error.CHECKIN_NOT_OPEN, new Rejection(409, "VENUE_CHECKIN_NOT_OPEN")),
            Map.entry(Error.CODE_NOT_VALID, new Rejection(409, "VENUE_CODE_NOT_VALID")));

    private final VenueBookingService service = mock(VenueBookingService.class);
    private final IdentityProfilePort profiles = mock(IdentityProfilePort.class);
    private final TestingAuthenticationToken customer = signedIn(SUBJECT, CUSTOMER_ORGANIZATION, "CUSTOMER");
    private final TestingAuthenticationToken operator = signedIn(OPERATOR, OPERATOR_ORG, "PROVIDER_OPERATOR");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new VenueBookingController(service, profiles))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    // ---- 谁能调 ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("customerRoutes")
    void bookingRoutesRefuseAnAccountThatIsNotACustomer(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request.principal(operator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(service, profiles);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allRoutes")
    void noRouteWorksWithoutASignedInSubject(String route, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(service, profiles);
    }

    @Test
    void checkInNeedsAProviderOperatorOfAnOrganization() throws Exception {
        for (TestingAuthenticationToken who : List.of(
                customer,
                signedIn(OPERATOR, null, "PROVIDER_OPERATOR"),
                signedIn(OPERATOR, OPERATOR_ORG, "PROVIDER_ADMIN"))) {
            mockMvc.perform(checkInRequest().principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(service);
        when(service.checkIn(OPERATOR, OPERATOR_ORG, CODE)).thenReturn(new VenueCheckinView(
                CODE, "东营低空与机器人体验馆", "机器人避障闯关", STARTS_AT, 2, STARTS_AT.minusMinutes(10), false));

        mockMvc.perform(checkInRequest().principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$.code").value(CODE))
                .andExpect(jsonPath("$.people").value(2))
                .andExpect(jsonPath("$.repeated").value(false));
    }

    // ---- 参与人和预约 ----

    @Test
    void participantsAreReadForTheSignedInAccountByItsDisplayName() throws Exception {
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.of(new IdentityProfile(SUBJECT, "王女士（示例）", "东营示例物流公司", "CUSTOMER")));
        when(service.listParticipants(SUBJECT, "王女士（示例）", SESSION)).thenReturn(new VenueParticipantListView(
                SESSION, 6, 10, List.of(
                        new VenueParticipantView(SELF, VenueParticipantRelation.SELF, "王女士（示例）", null, true,
                                VenueEligibilityReason.ELIGIBLE),
                        new VenueParticipantView(CHILD, VenueParticipantRelation.CHILD, "小宝", 5, false,
                                VenueEligibilityReason.UNDER_MIN_AGE))));

        mockMvc.perform(get("/api/v1/venue-sessions/{id}/participants", SESSION).principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatsLeft").value(6))
                .andExpect(jsonPath("$.maxPeople").value(10))
                .andExpect(jsonPath("$.items[0].length()").value(6))
                .andExpect(jsonPath("$.items[0].ageAtSession").value(nullValue()))
                .andExpect(jsonPath("$.items[1].eligible").value(false))
                .andExpect(jsonPath("$.items[1].reason").value("UNDER_MIN_AGE"));
    }

    @Test
    void aBookingIsCreatedWithItsVersionAsTheETagAndAReplayIsOk() throws Exception {
        BookCommand command = new BookCommand(SESSION, List.of(SELF, CHILD), 7800L, true);
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.empty());
        when(service.book(SUBJECT, null, KEY, command))
                .thenReturn(new BookingResult(booking(VenueBookingStatus.PENDING_PAYMENT, 0), true))
                .thenReturn(new BookingResult(booking(VenueBookingStatus.PENDING_PAYMENT, 0), false));

        mockMvc.perform(bookRequest("{\"sessionId\":\"%s\",\"participantIds\":[\"%s\",\"%s\"],\"expectedAmountMinor\":7800,"
                        + "\"noticeAgreed\":true}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.length()").value(16))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.code").value(nullValue()))
                .andExpect(jsonPath("$.payment.length()").value(5))
                .andExpect(jsonPath("$.payment.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payment.tradeOrderId").value(ORDER.toString()))
                .andExpect(jsonPath("$.payment.amountMinor").value(7800))
                .andExpect(jsonPath("$.payment.payBefore").value("2026-11-19T02:15:00Z"))
                .andExpect(jsonPath("$.venue.length()").value(4))
                .andExpect(jsonPath("$.project.length()").value(3))
                .andExpect(jsonPath("$.session.startsAt").value("2026-11-21T02:00:00Z"))
                .andExpect(jsonPath("$.participants[1].age").value(10))
                .andExpect(jsonPath("$.refund").value(nullValue()));
        mockMvc.perform(bookRequest("{\"sessionId\":\"%s\",\"participantIds\":[\"%s\",\"%s\"],\"expectedAmountMinor\":7800,"
                        + "\"noticeAgreed\":true}"))
                .andExpect(status().isOk());
    }

    // 没写或写成 null 的“同意到馆须知”按没同意交给服务，由服务拒绝；缺幂等键是 400。
    @Test
    void aMissingNoticeAgreementOrIdempotencyKeyIsABadRequest() throws Exception {
        when(service.book(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("booking is invalid"));

        mockMvc.perform(bookRequest("{\"sessionId\":\"%s\",\"participantIds\":[\"%s\",\"%s\"],\"expectedAmountMinor\":7800}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        verify(service).book(SUBJECT, null, KEY, new BookCommand(SESSION, List.of(SELF, CHILD), 7800L, false));
        mockMvc.perform(post("/api/v1/venue-bookings").principal(customer)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void reschedulingTakesTheVersionFromIfMatchAndAnswersWithTheNewETag() throws Exception {
        when(service.reschedule(SUBJECT, BOOKING, 3, LATER_SESSION)).thenReturn(booking(VenueBookingStatus.CONFIRMED, 4));
        String body = "{\"sessionId\":\"" + LATER_SESSION + "\"}";

        for (String ifMatch : List.of("\"3\"", "W/\"3\"", "3")) {
            mockMvc.perform(post("/api/v1/venue-bookings/{id}/reschedule", BOOKING)
                            .principal(customer)
                            .header("If-Match", ifMatch)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"4\""))
                    .andExpect(jsonPath("$.code").value(CODE));
        }
        for (String ifMatch : List.of("abc", "-1")) {
            mockMvc.perform(post("/api/v1/venue-bookings/{id}/reschedule", BOOKING)
                            .principal(customer)
                            .header("If-Match", ifMatch)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/v1/venue-bookings/{id}/reschedule", BOOKING)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cancelListAndReadGoStraightToTheService() throws Exception {
        when(service.cancel(SUBJECT, BOOKING)).thenReturn(booking(VenueBookingStatus.CANCELLED, 2));
        when(service.readBooking(SUBJECT, BOOKING)).thenReturn(booking(VenueBookingStatus.CONFIRMED, 1));
        when(service.listBookings(SUBJECT, "FINISHED", 2, 10))
                .thenReturn(new VenueBookingPageView(2, 10, 11, false, List.of(booking(VenueBookingStatus.EXPIRED, 1))));

        mockMvc.perform(post("/api/v1/venue-bookings/{id}/cancel", BOOKING).principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/v1/venue-bookings/{id}", BOOKING).principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));
        mockMvc.perform(get("/api/v1/venue-bookings").principal(customer)
                        .param("bucket", "FINISHED").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.items[0].status").value("EXPIRED"));
    }

    @ParameterizedTest
    @EnumSource(Error.class)
    void everyBusinessRejectionMapsToItsStatusAndCode(Error error) throws Exception {
        Rejection expected = REJECTIONS.get(error);
        assertThat(expected).as("no expected status and code for " + error).isNotNull();
        when(service.readBooking(SUBJECT, BOOKING)).thenThrow(new VenueBookingException(error));

        mockMvc.perform(get("/api/v1/venue-bookings/{id}", BOOKING)
                        .principal(customer)
                        .header("X-Request-Id", "trace-1"))
                .andExpect(status().is(expected.status()))
                .andExpect(jsonPath("$.code").value(expected.code()))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value("trace-1"));
    }

    @Test
    void everyVenueErrorCodeBelongsToOneRejection() {
        assertThat(REJECTIONS).hasSize(Error.values().length);
        assertThat(REJECTIONS.values().stream().map(Rejection::code)).doesNotHaveDuplicates();
        assertThat(REJECTIONS.values().stream().map(Rejection::code).filter(code -> code.startsWith("VENUE_")))
                .hasSize(12);
    }

    // ---- 辅助 ----

    private MockHttpServletRequestBuilder bookRequest(String template) {
        return post("/api/v1/venue-bookings")
                .principal(customer)
                .header("Idempotency-Key", KEY.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(template.formatted(SESSION, SELF, CHILD));
    }

    private static MockHttpServletRequestBuilder checkInRequest() {
        return post("/api/v1/venue-checkins")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + CODE + "\"}");
    }

    private static Stream<Arguments> customerRoutes() {
        String booking = "/api/v1/venue-bookings/" + BOOKING;
        return Stream.of(
                Arguments.of("GET participants", get("/api/v1/venue-sessions/" + SESSION + "/participants")),
                Arguments.of("POST bookings", post("/api/v1/venue-bookings")
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + SESSION + "\"}")),
                Arguments.of("GET bookings", get("/api/v1/venue-bookings")),
                Arguments.of("GET booking", get(booking)),
                Arguments.of("POST reschedule", post(booking + "/reschedule")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + LATER_SESSION + "\"}")),
                Arguments.of("POST cancel", post(booking + "/cancel")));
    }

    private static Stream<Arguments> allRoutes() {
        return Stream.concat(customerRoutes(), Stream.of(Arguments.of("POST checkins", checkInRequest())));
    }

    private static VenueBookingView booking(VenueBookingStatus status, int version) {
        boolean pending = status == VenueBookingStatus.PENDING_PAYMENT;
        return new VenueBookingView(
                BOOKING,
                status,
                new BookingVenueView(UUID.fromString("b2000000-0000-4000-8000-000000000001"), "东营低空与机器人体验馆",
                        "东营区示例路 66 号", null),
                new BookingProjectView(UUID.fromString("b4000000-0000-4000-8000-000000000101"), "机器人避障闯关", 30),
                new BookingSessionView(SESSION, STARTS_AT, STARTS_AT.plusMinutes(30)),
                2,
                List.of(
                        new BookedParticipantView(SELF, "王女士（示例）", VenueParticipantRelation.SELF, null),
                        new BookedParticipantView(CHILD, "小明", VenueParticipantRelation.CHILD, 10)),
                pending ? null : CODE,
                new VenueBookingPaymentView(pending ? VenuePaymentStatus.PENDING_PAYMENT : VenuePaymentStatus.PAID,
                        ORDER, 7800, 3900, OffsetDateTime.parse("2026-11-19T02:15:00Z")),
                null,
                OffsetDateTime.parse("2026-11-19T02:00:00Z"),
                null,
                null,
                true,
                !pending,
                version);
    }

    private static TestingAuthenticationToken signedIn(UUID subjectId, UUID organizationId, String role) {
        return new TestingAuthenticationToken(
                new CurrentSubject(subjectId, Set.of(role), organizationId, Set.of(), 0), null);
    }

    private record Rejection(int status, String code) {
    }
}
