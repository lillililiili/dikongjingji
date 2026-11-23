package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 控制器只做三件事：从令牌里取账号和角色、把请求转成服务的参数、把结果和业务拒绝转成契约里的状态码。
 * 没带令牌时的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里对十个接口逐个检查；
 * 这里的 standaloneSetup 不带过滤器。
 */
class ActivityEnrollmentControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ORGANIZATION = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID OPERATOR = UUID.fromString("a1000000-0000-4000-8000-0000000000a4");
    private static final UUID ORGANIZER = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000004");
    private static final UUID SESSION = UUID.fromString("a3000000-0000-4000-8000-000000000401");
    private static final UUID SELF = UUID.fromString("a4000000-0000-4000-8000-000000000001");
    private static final UUID CHILD = UUID.fromString("a4000000-0000-4000-8000-000000000002");
    private static final UUID ENROLLMENT = UUID.fromString("a5000000-0000-4000-8000-000000000001");
    private static final UUID TICKET = UUID.fromString("a6000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2026-11-28T01:00:00Z");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-11-17T04:00:00Z");
    private static final String TICKET_CODE = "ACT-7K2M-9QXD-4HTP";

    /** 每一种业务拒绝对外的状态码和错误码（GlobalExceptionHandler 与契约 1.1.0）。 */
    private static final Map<Error, Rejection> REJECTIONS = Map.ofEntries(
            Map.entry(Error.NOT_FOUND, new Rejection(404, "COMMON_NOT_FOUND")),
            Map.entry(Error.FORBIDDEN, new Rejection(403, "COMMON_FORBIDDEN")),
            Map.entry(Error.IDEMPOTENCY_CONFLICT, new Rejection(409, "ACT_IDEMPOTENCY_KEY_REUSED")),
            Map.entry(Error.PARTICIPANT_DUPLICATE, new Rejection(409, "ACT_PARTICIPANT_DUPLICATE")),
            Map.entry(Error.PARTICIPANT_LIMIT, new Rejection(409, "ACT_PARTICIPANT_LIMIT")),
            Map.entry(Error.PARTICIPANT_NOT_ELIGIBLE, new Rejection(422, "ACT_PARTICIPANT_NOT_ELIGIBLE")),
            Map.entry(Error.PARTICIPANT_ALREADY_ENROLLED, new Rejection(409, "ACT_PARTICIPANT_ALREADY_ENROLLED")),
            Map.entry(Error.ENROLLMENT_UNSUPPORTED, new Rejection(409, "ACT_ENROLLMENT_UNSUPPORTED")),
            Map.entry(Error.ENROLLMENT_NOT_OPEN, new Rejection(409, "ACT_ENROLLMENT_NOT_OPEN")),
            Map.entry(Error.SESSION_FULL, new Rejection(409, "ACT_SESSION_FULL")),
            Map.entry(Error.CANCEL_NOT_ALLOWED, new Rejection(409, "ACT_CANCEL_NOT_ALLOWED")),
            Map.entry(Error.CHECKIN_NOT_OPEN, new Rejection(409, "ACT_CHECKIN_NOT_OPEN")),
            Map.entry(Error.TICKET_NOT_VALID, new Rejection(409, "ACT_TICKET_NOT_VALID")),
            // E09 收费报名：错误码用 ACTIVITY_ 前缀（docs/redesign/03-App实现任务计划.md §20.4）。
            Map.entry(Error.AMOUNT_CHANGED, new Rejection(409, "ACTIVITY_AMOUNT_CHANGED")),
            Map.entry(Error.PAYMENT_UNAVAILABLE, new Rejection(409, "ACTIVITY_PAYMENT_UNAVAILABLE")),
            // S5-3
            Map.entry(Error.WAITLIST_NOT_AVAILABLE, new Rejection(409, "ACT_WAITLIST_NOT_AVAILABLE")),
            Map.entry(Error.WAITLIST_ALREADY_JOINED, new Rejection(409, "ACT_WAITLIST_ALREADY_JOINED")),
            Map.entry(Error.WAITLIST_STATE_INVALID, new Rejection(409, "ACT_WAITLIST_STATE_INVALID")),
            Map.entry(Error.RESCHEDULE_NOT_ALLOWED, new Rejection(409, "ACT_RESCHEDULE_NOT_ALLOWED")),
            Map.entry(Error.RESULT_NOT_ALLOWED, new Rejection(409, "ACT_RESULT_NOT_ALLOWED")),
            Map.entry(Error.RESULT_VERSION_CONFLICT, new Rejection(409, "ACT_RESULT_VERSION_CONFLICT")));

    private final ActivityEnrollmentService service = mock(ActivityEnrollmentService.class);
    private final IdentityProfilePort profiles = mock(IdentityProfilePort.class);
    private final TestingAuthenticationToken customer = signedIn(SUBJECT, CUSTOMER_ORGANIZATION, "CUSTOMER");
    private final TestingAuthenticationToken operator = signedIn(OPERATOR, ORGANIZER, "PROVIDER_OPERATOR");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // standaloneSetup 默认的 Jackson 把时间写成数字；这里换成与应用一致的配置（ISO 文本），否则断言不到真实格式。
        mockMvc = MockMvcBuilders.standaloneSetup(new ActivityEnrollmentController(service, profiles))
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
    void participantAndEnrollmentRoutesRefuseAnAccountThatIsNotACustomer(
            String route,
            MockHttpServletRequestBuilder request
    ) throws Exception {
        mockMvc.perform(request.principal(operator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"))
                .andExpect(jsonPath("$.details").value(nullValue()));

        verifyNoInteractions(service, profiles);
    }

    // 过滤器之后如果没有登录主体（正常不会发生），控制器自己也不放行，更不会替它选一个账号。
    @ParameterizedTest(name = "{0}")
    @MethodSource("allRoutes")
    void noRouteWorksWithoutASignedInSubject(String route, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(service, profiles);
    }

    @Test
    void checkInNeedsAProviderOperatorThatBelongsToAnOrganization() throws Exception {
        TestingAuthenticationToken operatorWithoutOrganization = signedIn(OPERATOR, null, "PROVIDER_OPERATOR");
        TestingAuthenticationToken providerAdmin = signedIn(OPERATOR, ORGANIZER, "PROVIDER_ADMIN");

        for (TestingAuthenticationToken refused : List.of(customer, operatorWithoutOrganization, providerAdmin)) {
            mockMvc.perform(checkInRequest().principal(refused))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }

        verifyNoInteractions(service);
    }

    // 账号只来自令牌：请求里再写一个别人的账号也没有用。
    @Test
    void theAccountComesFromTheTokenAndFromNowhereElse() throws Exception {
        UUID someoneElse = UUID.fromString("11111111-1111-1111-1111-222222222222");
        when(service.listEnrollments(SUBJECT, null, 1, 20))
                .thenReturn(new EnrollmentPageView(1, 20, 0, false, List.of()));

        mockMvc.perform(get("/api/v1/activity-enrollments")
                        .principal(customer)
                        .param("accountId", someoneElse.toString())
                        .param("ownerId", someoneElse.toString()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/activity-participants")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"小明","birthMonth":"2018-05","guardianConfirmed":true,
                                 "ownerId":"%s","accountId":"%s"}
                                """.formatted(someoneElse, someoneElse)))
                .andExpect(status().isCreated());

        verify(service).listEnrollments(SUBJECT, null, 1, 20);
        verify(service).addChild(SUBJECT, "小明", "2018-05", true);
    }

    // 生产里登录主体是 JWT：账号、角色、机构都从它的声明里取。
    @Test
    void aJwtPrincipalGivesTheSameSubjectRolesAndOrganization() throws Exception {
        when(service.readEnrollment(SUBJECT, ENROLLMENT)).thenReturn(enrollment());
        when(service.checkIn(OPERATOR, ORGANIZER, TICKET_CODE)).thenReturn(checkin(false));

        mockMvc.perform(get("/api/v1/activity-enrollments/{id}", ENROLLMENT)
                        .principal(jwt(SUBJECT.toString(), List.of("CUSTOMER"), CUSTOMER_ORGANIZATION.toString())))
                .andExpect(status().isOk());
        mockMvc.perform(checkInRequest()
                        .principal(jwt(OPERATOR.toString(), List.of("PROVIDER_OPERATOR"), ORGANIZER.toString())))
                .andExpect(status().isOk());

        verify(service).readEnrollment(SUBJECT, ENROLLMENT);
        verify(service).checkIn(OPERATOR, ORGANIZER, TICKET_CODE);
    }

    @Test
    void aJwtWithoutUsableClaimsIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/activity-enrollments/{id}", ENROLLMENT)
                        .principal(jwt("not-a-uuid", List.of("CUSTOMER"), null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/activity-enrollments/{id}", ENROLLMENT)
                        .principal(jwt(SUBJECT.toString(), null, null)))
                .andExpect(status().isForbidden());
        // 操作员的令牌里没有机构，或者机构不是合法的 ID，都不能核销。
        mockMvc.perform(checkInRequest().principal(jwt(OPERATOR.toString(), List.of("PROVIDER_OPERATOR"), " ")))
                .andExpect(status().isForbidden());
        mockMvc.perform(checkInRequest()
                        .principal(jwt(OPERATOR.toString(), List.of("PROVIDER_OPERATOR"), "not-a-uuid")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    // ---- 参与人 ----

    @Test
    void listsParticipantsWithTheAccountsDisplayNameAndTheSessionAskedFor() throws Exception {
        when(profiles.findProfile(SUBJECT))
                .thenReturn(Optional.of(new IdentityProfile(SUBJECT, "王女士（示例）", "东营示例物流公司", "CUSTOMER")));
        when(service.listParticipants(SUBJECT, "王女士（示例）", SESSION)).thenReturn(new ParticipantListView(List.of(
                new ParticipantView(SELF, ParticipantRelation.SELF, "王女士（示例）", null, null, false,
                        new ParticipantEligibilityView(true, ParticipantEligibilityReason.ELIGIBLE, null)),
                new ParticipantView(CHILD, ParticipantRelation.CHILD, "小明", "2018-05", 8, true,
                        new ParticipantEligibilityView(false, ParticipantEligibilityReason.UNDER_MIN_AGE, 8)))));

        mockMvc.perform(get("/api/v1/activity-participants")
                        .principal(customer)
                        .param("sessionId", SESSION.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].participantId").value(SELF.toString()))
                .andExpect(jsonPath("$.items[0].relation").value("SELF"))
                .andExpect(jsonPath("$.items[0].birthMonth").value(nullValue()))
                .andExpect(jsonPath("$.items[0].age").value(nullValue()))
                .andExpect(jsonPath("$.items[0].eligibility.eligible").value(true))
                .andExpect(jsonPath("$.items[0].eligibility.ageAtSession").value(nullValue()))
                .andExpect(jsonPath("$.items[1].relation").value("CHILD"))
                .andExpect(jsonPath("$.items[1].name").value("小明"))
                .andExpect(jsonPath("$.items[1].birthMonth").value("2018-05"))
                .andExpect(jsonPath("$.items[1].age").value(8))
                .andExpect(jsonPath("$.items[1].photoUseAuthorized").value(true))
                .andExpect(jsonPath("$.items[1].eligibility.eligible").value(false))
                .andExpect(jsonPath("$.items[1].eligibility.reason").value("UNDER_MIN_AGE"))
                .andExpect(jsonPath("$.items[1].eligibility.ageAtSession").value(8))
                // 参与人只有契约里的七个字段，没有所属账号。
                .andExpect(jsonPath("$.items[1].length()").value(7))
                .andExpect(jsonPath("$.items[1].ownerId").doesNotExist());
    }

    @Test
    void listsParticipantsWithoutASessionAndWithoutAProfile() throws Exception {
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.empty());
        when(service.listParticipants(SUBJECT, null, null)).thenReturn(new ParticipantListView(List.of(
                new ParticipantView(SELF, ParticipantRelation.SELF, "本人", null, null, false, null))));

        mockMvc.perform(get("/api/v1/activity-participants").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("本人"))
                .andExpect(jsonPath("$.items[0].eligibility").value(nullValue()));

        verify(service).listParticipants(SUBJECT, null, null);
    }

    @Test
    void addingAChildAnswersCreatedWithTheNewParticipant() throws Exception {
        when(service.addChild(SUBJECT, "小明", "2018-05", true)).thenReturn(child("小明", false));

        mockMvc.perform(post("/api/v1/activity-participants")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"小明\",\"birthMonth\":\"2018-05\",\"guardianConfirmed\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.participantId").value(CHILD.toString()))
                .andExpect(jsonPath("$.relation").value("CHILD"))
                .andExpect(jsonPath("$.name").value("小明"))
                .andExpect(jsonPath("$.birthMonth").value("2018-05"))
                .andExpect(jsonPath("$.age").value(8))
                .andExpect(jsonPath("$.photoUseAuthorized").value(false))
                .andExpect(jsonPath("$.eligibility").value(nullValue()));
    }

    // 没写或写成 null 的“监护人确认”按没确认交给服务，由服务拒绝。
    @Test
    void aMissingGuardianConfirmationReachesTheServiceAsFalse() throws Exception {
        when(service.addChild(SUBJECT, "小明", "2018-05", false))
                .thenThrow(new IllegalArgumentException("guardian confirmation is required"));

        for (String body : List.of(
                "{\"name\":\"小明\",\"birthMonth\":\"2018-05\"}",
                "{\"name\":\"小明\",\"birthMonth\":\"2018-05\",\"guardianConfirmed\":null}",
                "{\"name\":\"小明\",\"birthMonth\":\"2018-05\",\"guardianConfirmed\":false}")) {
            mockMvc.perform(post("/api/v1/activity-participants")
                            .principal(customer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }

        verify(service, never()).addChild(any(), any(), any(), eq(true));
    }

    @Test
    void renamesAParticipantWithPut() throws Exception {
        when(service.renameParticipant(SUBJECT, CHILD, "小明明")).thenReturn(child("小明明", false));

        mockMvc.perform(put("/api/v1/activity-participants/{id}", CHILD)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"小明明\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participantId").value(CHILD.toString()))
                .andExpect(jsonPath("$.name").value("小明明"));
    }

    @Test
    void photoUseIsSetAndWithdrawnAndMustBeStated() throws Exception {
        when(service.setPhotoUse(SUBJECT, CHILD, true)).thenReturn(child("小明", true));
        when(service.setPhotoUse(SUBJECT, CHILD, false)).thenReturn(child("小明", false));

        mockMvc.perform(photoUse("{\"authorized\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUseAuthorized").value(true));
        mockMvc.perform(photoUse("{\"authorized\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUseAuthorized").value(false));
        verify(service).setPhotoUse(SUBJECT, CHILD, true);
        verify(service).setPhotoUse(SUBJECT, CHILD, false);

        // 授权不能靠猜：没写或写成 null 都是 400，不会被当成“撤回”。
        for (String body : List.of("{}", "{\"authorized\":null}")) {
            mockMvc.perform(photoUse(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }
        verifyNoMoreInteractions(service);
    }

    @Test
    void deletingAChildAnswersNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/activity-participants/{id}", CHILD).principal(customer))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).deleteChild(SUBJECT, CHILD);
    }

    // ---- 报名 ----

    @Test
    void theFirstEnrollmentIsCreatedAndAReplayIsOk() throws Exception {
        EnrollCommand command = new EnrollCommand(ACTIVITY, SESSION, List.of(SELF, CHILD), true);
        when(service.enroll(SUBJECT, KEY, command))
                .thenReturn(new EnrollmentResult(enrollment(), true))
                .thenReturn(new EnrollmentResult(enrollment(), false));

        mockMvc.perform(enrollRequest(KEY.toString(), true))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enrollmentId").value(ENROLLMENT.toString()))
                .andExpect(jsonPath("$.tickets.length()").value(2));
        mockMvc.perform(enrollRequest(KEY.toString(), true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentId").value(ENROLLMENT.toString()))
                // 返回的是报名单本身，created 只体现在状态码上。
                .andExpect(jsonPath("$.created").doesNotExist());

        verify(service, times(2)).enroll(SUBJECT, KEY, command);
    }

    // 收费报名（E09）：页面看到的应付金额原样交给服务；返回的报名单带付款信息，待付款的凭证没有凭证号。
    @Test
    void aPaidEnrollmentCarriesTheExpectedAmountAndAnswersWithItsPayment() throws Exception {
        EnrollCommand command = new EnrollCommand(ACTIVITY, SESSION, List.of(SELF, CHILD), true, 24_000L);
        UUID tradeOrder = UUID.fromString("b1000000-0000-4000-8000-000000000001");
        EnrollmentView base = enrollment();
        EnrollmentView pending = new EnrollmentView(base.enrollmentId(), base.status(), base.activity(), base.session(),
                base.location(), base.createdAt(), base.cancellableUntil(), true,
                List.of(new TicketView(TICKET, SELF, "王女士（示例）", ParticipantRelation.SELF, null, null,
                        TicketStatus.PENDING_PAYMENT, null, null, null)),
                new EnrollmentPaymentView(EnrollmentPaymentStatus.PENDING_PAYMENT, tradeOrder, 24_000L, 12_000L,
                        CREATED_AT.plusMinutes(15)));
        when(service.enroll(SUBJECT, KEY, command)).thenReturn(new EnrollmentResult(pending, true));

        mockMvc.perform(post("/api/v1/activity-enrollments")
                        .principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"activityId\":\"%s\",\"sessionId\":\"%s\",\"participantIds\":[\"%s\",\"%s\"],"
                                + "\"noticeAgreed\":true,\"expectedAmountMinor\":24000}")
                                .formatted(ACTIVITY, SESSION, SELF, CHILD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.payment.tradeOrderId").value(tradeOrder.toString()))
                .andExpect(jsonPath("$.payment.amountMinor").value(24_000))
                .andExpect(jsonPath("$.payment.unitAmountMinor").value(12_000))
                .andExpect(jsonPath("$.payment.payBefore").isNotEmpty())
                .andExpect(jsonPath("$.tickets[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.tickets[0].ticketCode").value(nullValue()))
                .andExpect(jsonPath("$.tickets[0].refund").value(nullValue()));

        verify(service).enroll(SUBJECT, KEY, command);
    }

    // 没写或写成 null 的“同意报名须知”按没同意交给服务，由服务拒绝。
    @Test
    void aMissingNoticeAgreementReachesTheServiceAsFalse() throws Exception {
        EnrollCommand notAgreed = new EnrollCommand(ACTIVITY, SESSION, List.of(SELF, CHILD), false);
        when(service.enroll(SUBJECT, KEY, notAgreed)).thenThrow(new IllegalArgumentException("enrollment is invalid"));

        for (Boolean noticeAgreed : new Boolean[] {null, false}) {
            mockMvc.perform(enrollRequest(KEY.toString(), noticeAgreed))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }
        mockMvc.perform(post("/api/v1/activity-enrollments")
                        .principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":\"%s\",\"sessionId\":\"%s\",\"participantIds\":[\"%s\",\"%s\"]}"
                                .formatted(ACTIVITY, SESSION, SELF, CHILD)))
                .andExpect(status().isBadRequest());

        verify(service, times(3)).enroll(SUBJECT, KEY, notAgreed);
    }

    @Test
    void enrollingWithoutAUsableIdempotencyKeyOrBodyIsABadRequest() throws Exception {
        mockMvc.perform(enrollRequest(null, true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(enrollRequest("not-a-uuid", true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/activity-enrollments")
                        .principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/activity-enrollments")
                        .principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(service);
    }

    @Test
    void listsEnrollmentsWithTheContractDefaultsAndWithEveryFilter() throws Exception {
        when(service.listEnrollments(SUBJECT, null, 1, 20))
                .thenReturn(new EnrollmentPageView(1, 20, 0, false, List.of()));
        when(service.listEnrollments(SUBJECT, "FINISHED", 2, 10))
                .thenReturn(new EnrollmentPageView(2, 10, 11, false, List.of(enrollment())));

        mockMvc.perform(get("/api/v1/activity-enrollments").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get("/api/v1/activity-enrollments")
                        .principal(customer)
                        .param("status", "FINISHED")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items[0].enrollmentId").value(ENROLLMENT.toString()));

        verify(service).listEnrollments(SUBJECT, null, 1, 20);
        verify(service).listEnrollments(SUBJECT, "FINISHED", 2, 10);
    }

    @Test
    void readsOneEnrollmentWithItsTicketsAsTheContractDescribes() throws Exception {
        when(service.readEnrollment(SUBJECT, ENROLLMENT)).thenReturn(enrollment());

        mockMvc.perform(get("/api/v1/activity-enrollments/{id}", ENROLLMENT).principal(customer))
                .andExpect(status().isOk())
                // 1.4.0 加了 kind、team、reschedule、changes、reschedulable。
                .andExpect(jsonPath("$.length()").value(15))
                // 1.2.0：免费报名的付款只有 FREE，其余为 null。
                .andExpect(jsonPath("$.payment.length()").value(5))
                .andExpect(jsonPath("$.payment.status").value("FREE"))
                .andExpect(jsonPath("$.payment.tradeOrderId").value(nullValue()))
                .andExpect(jsonPath("$.tickets[0].refund").value(nullValue()))
                .andExpect(jsonPath("$.enrollmentId").value(ENROLLMENT.toString()))
                .andExpect(jsonPath("$.status").value("UPCOMING"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-17T04:00:00Z"))
                .andExpect(jsonPath("$.cancellableUntil").value("2026-11-28T01:00:00Z"))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.activity.length()").value(6))
                .andExpect(jsonPath("$.activity.activityId").value(ACTIVITY.toString()))
                .andExpect(jsonPath("$.activity.type").value("OPEN_DAY"))
                .andExpect(jsonPath("$.activity.title").value("无人机开放体验日"))
                .andExpect(jsonPath("$.activity.coverImage").value(nullValue()))
                .andExpect(jsonPath("$.activity.cityCode").value("370500"))
                .andExpect(jsonPath("$.activity.organizerName").value("东营低空与机器人体验馆"))
                .andExpect(jsonPath("$.session.length()").value(5))
                .andExpect(jsonPath("$.session.sessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.session.label").value("上午场"))
                .andExpect(jsonPath("$.session.startsAt").value("2026-11-28T01:00:00Z"))
                .andExpect(jsonPath("$.session.endsAt").value(nullValue()))
                .andExpect(jsonPath("$.session.checkInOpensAt").value(nullValue()))
                .andExpect(jsonPath("$.location.name").value("体验馆一层大厅"))
                .andExpect(jsonPath("$.location.latitude").value(37.43))
                .andExpect(jsonPath("$.tickets[0].length()").value(11))
                .andExpect(jsonPath("$.tickets[0].ticketId").value(TICKET.toString()))
                .andExpect(jsonPath("$.tickets[0].participantId").value(SELF.toString()))
                .andExpect(jsonPath("$.tickets[0].participantName").value("王女士（示例）"))
                .andExpect(jsonPath("$.tickets[0].relation").value("SELF"))
                .andExpect(jsonPath("$.tickets[0].age").value(nullValue()))
                .andExpect(jsonPath("$.tickets[0].ticketCode").value(TICKET_CODE))
                .andExpect(jsonPath("$.tickets[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.tickets[0].checkedInAt").value(nullValue()))
                .andExpect(jsonPath("$.tickets[0].cancelledAt").value(nullValue()))
                .andExpect(jsonPath("$.tickets[1].relation").value("CHILD"))
                .andExpect(jsonPath("$.tickets[1].age").value(8))
                .andExpect(jsonPath("$.tickets[1].status").value("CHECKED_IN"))
                .andExpect(jsonPath("$.tickets[1].checkedInAt").value("2026-11-28T00:40:00Z"))
                // 报名单里没有账号、幂等键这些内部字段。
                .andExpect(jsonPath("$.accountId").doesNotExist())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());
    }

    @Test
    void cancelPassesTheTicketsNamedOrNoneAtAll() throws Exception {
        when(service.cancel(eq(SUBJECT), eq(ENROLLMENT), any(), any())).thenReturn(enrollment());
        String route = "/api/v1/activity-enrollments/{id}/cancel";

        // 不带请求体、空对象、写成 null：都是“取消全部还没用的凭证”。
        mockMvc.perform(post(route, ENROLLMENT).principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrollmentId").value(ENROLLMENT.toString()));
        mockMvc.perform(post(route, ENROLLMENT)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(route, ENROLLMENT)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketIds\":null}"))
                .andExpect(status().isOk());
        verify(service, times(3)).cancel(SUBJECT, ENROLLMENT, null, null);

        mockMvc.perform(post(route, ENROLLMENT)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketIds\":[\"%s\"]}".formatted(TICKET)))
                .andExpect(status().isOk());
        verify(service).cancel(SUBJECT, ENROLLMENT, List.of(TICKET), null);

        // S5-3：申请原因原样交给服务（由服务校验）。
        mockMvc.perform(post(route, ENROLLMENT)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketIds\":[\"%s\"],\"reason\":\"TIME_CONFLICT\"}".formatted(TICKET)))
                .andExpect(status().isOk());
        verify(service).cancel(SUBJECT, ENROLLMENT, List.of(TICKET), "TIME_CONFLICT");
    }

    // ---- 核销 ----

    @Test
    void checkInUsesTheOperatorAndOrganizationOfTheToken() throws Exception {
        when(service.checkIn(OPERATOR, ORGANIZER, TICKET_CODE)).thenReturn(checkin(false));

        mockMvc.perform(checkInRequest().principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$.ticketCode").value(TICKET_CODE))
                .andExpect(jsonPath("$.participantName").value("王女士（示例）"))
                .andExpect(jsonPath("$.activityTitle").value("无人机开放体验日"))
                .andExpect(jsonPath("$.sessionLabel").value(nullValue()))
                .andExpect(jsonPath("$.sessionStartsAt").value("2026-11-28T01:00:00Z"))
                .andExpect(jsonPath("$.checkedInAt").value("2026-11-28T00:40:00Z"))
                .andExpect(jsonPath("$.repeated").value(false));
    }

    // 同一张凭证再核销一次也是 200，repeated 告诉操作员这是重复的。
    @Test
    void aRepeatedCheckInIsStillOk() throws Exception {
        when(service.checkIn(OPERATOR, ORGANIZER, TICKET_CODE)).thenReturn(checkin(true));

        mockMvc.perform(checkInRequest().principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repeated").value(true))
                .andExpect(jsonPath("$.checkedInAt").value("2026-11-28T00:40:00Z"));
    }

    @Test
    void aCheckInWithoutACodeIsLeftToTheServiceToReject() throws Exception {
        when(service.checkIn(OPERATOR, ORGANIZER, null)).thenThrow(new IllegalArgumentException("ticket code is invalid"));

        mockMvc.perform(post("/api/v1/activity-checkins")
                        .principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/activity-checkins")
                        .principal(operator)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verify(service).checkIn(OPERATOR, ORGANIZER, null);
    }

    // ---- 拒绝与参数错误 ----

    @ParameterizedTest
    @EnumSource(Error.class)
    void everyBusinessRejectionMapsToItsStatusAndCode(Error error) throws Exception {
        Rejection expected = REJECTIONS.get(error);
        assertThat(expected).as("no expected status and code for " + error).isNotNull();
        when(service.readEnrollment(SUBJECT, ENROLLMENT)).thenThrow(new ActivityEnrollmentException(error));

        mockMvc.perform(get("/api/v1/activity-enrollments/{id}", ENROLLMENT)
                        .principal(customer)
                        .header("X-Request-Id", "trace-1"))
                .andExpect(status().is(expected.status()))
                .andExpect(jsonPath("$.code").value(expected.code()))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.details").value(nullValue()));
    }

    // 每个错误码只对应一种拒绝，客户端可以只看 code。
    @Test
    void everyActivityErrorCodeBelongsToOneRejection() {
        assertThat(REJECTIONS).hasSize(Error.values().length);
        assertThat(REJECTIONS.values().stream().map(Rejection::code)).doesNotHaveDuplicates();
        assertThat(REJECTIONS.values().stream().map(Rejection::code).filter(code -> code.startsWith("ACT_")))
                .hasSize(17);
        assertThat(REJECTIONS.values().stream().map(Rejection::code).filter(code -> code.startsWith("ACTIVITY_")))
                .hasSize(2);
    }

    @Test
    void malformedIdsAndArgumentsAreBadRequests() throws Exception {
        when(service.listEnrollments(SUBJECT, "LATER", 1, 20))
                .thenThrow(new IllegalArgumentException("enrollment status is invalid"));

        mockMvc.perform(get("/api/v1/activity-participants").principal(customer).param("sessionId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(delete("/api/v1/activity-participants/not-a-uuid").principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/activity-enrollments/not-a-uuid").principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/activity-enrollments").principal(customer).param("page", "one"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/activity-enrollments").principal(customer).param("status", "LATER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(put("/api/v1/activity-participants/{id}", CHILD)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    // ---- 辅助 ----

    private static Stream<Arguments> customerRoutes() {
        String participant = "/api/v1/activity-participants/" + CHILD;
        String enrollment = "/api/v1/activity-enrollments/" + ENROLLMENT;
        return Stream.of(
                route("GET participants", get("/api/v1/activity-participants")),
                route("POST participants", json(post("/api/v1/activity-participants"),
                        "{\"name\":\"小明\",\"birthMonth\":\"2018-05\",\"guardianConfirmed\":true}")),
                route("PUT participant", json(put(participant), "{\"name\":\"小明明\"}")),
                route("PUT participant photo-use", json(put(participant + "/photo-use"), "{\"authorized\":true}")),
                route("DELETE participant", delete(participant)),
                route("POST enrollments", json(post("/api/v1/activity-enrollments"), enrollBody(true))
                        .header("Idempotency-Key", KEY.toString())),
                route("GET enrollments", get("/api/v1/activity-enrollments")),
                route("GET enrollment", get(enrollment)),
                route("POST enrollment cancel", post(enrollment + "/cancel")));
    }

    private static Stream<Arguments> allRoutes() {
        return Stream.concat(customerRoutes(), Stream.of(route("POST checkins", checkInRequest())));
    }

    private static Arguments route(
            String name,
            MockHttpServletRequestBuilder request
    ) {
        return Arguments.of(name, request);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder checkInRequest() {
        return json(post("/api/v1/activity-checkins"), "{\"ticketCode\":\"" + TICKET_CODE + "\"}");
    }

    private MockHttpServletRequestBuilder photoUse(String body) {
        return json(put("/api/v1/activity-participants/{id}/photo-use", CHILD), body).principal(customer);
    }

    private MockHttpServletRequestBuilder enrollRequest(String idempotencyKey, Boolean noticeAgreed) {
        MockHttpServletRequestBuilder request =
                json(post("/api/v1/activity-enrollments"), enrollBody(noticeAgreed)).principal(customer);
        return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);
    }

    private static String enrollBody(Boolean noticeAgreed) {
        return "{\"activityId\":\"%s\",\"sessionId\":\"%s\",\"participantIds\":[\"%s\",\"%s\"],\"noticeAgreed\":%s}"
                .formatted(ACTIVITY, SESSION, SELF, CHILD, noticeAgreed);
    }

    private static TestingAuthenticationToken signedIn(UUID subjectId, UUID organizationId, String role) {
        return new TestingAuthenticationToken(
                new CurrentSubject(subjectId, Set.of(role), organizationId, Set.of(), 0), null);
    }

    private static TestingAuthenticationToken jwt(String subjectId, List<String> roles, String organizationId) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("subjectId", subjectId);
        if (roles != null) {
            builder.claim("roles", roles);
        }
        if (organizationId != null) {
            builder.claim("organizationId", organizationId);
        }
        return new TestingAuthenticationToken(builder.build(), null);
    }

    private static ParticipantView child(String name, boolean photoUseAuthorized) {
        return new ParticipantView(CHILD, ParticipantRelation.CHILD, name, "2018-05", 8, photoUseAuthorized, null);
    }

    private static EnrollmentView enrollment() {
        return new EnrollmentView(
                ENROLLMENT,
                EnrollmentStatus.UPCOMING,
                new EnrollmentActivityView(
                        ACTIVITY, ActivityType.OPEN_DAY, "无人机开放体验日", null, "370500", "东营低空与机器人体验馆"),
                new EnrollmentSessionView(SESSION, "上午场", STARTS_AT, null, null),
                new ActivityLocationView("体验馆一层大厅", "东营市东营区示例路 1 号", 37.43, 118.67),
                CREATED_AT,
                STARTS_AT,
                true,
                List.of(
                        new TicketView(TICKET, SELF, "王女士（示例）", ParticipantRelation.SELF, null,
                                TICKET_CODE, TicketStatus.ACTIVE, null, null, null),
                        new TicketView(UUID.fromString("a6000000-0000-4000-8000-000000000002"), CHILD, "小明",
                                ParticipantRelation.CHILD, 8, "ACT-3F8N-W1ZC-6RYB", TicketStatus.CHECKED_IN,
                                STARTS_AT.minusMinutes(20), null, null)),
                new EnrollmentPaymentView(EnrollmentPaymentStatus.FREE, null, null, null, null));
    }

    private static CheckinView checkin(boolean repeated) {
        return new CheckinView(
                TICKET_CODE, "王女士（示例）", "无人机开放体验日", null, STARTS_AT, STARTS_AT.minusMinutes(20), repeated);
    }

    private record Rejection(int status, String code) {
    }
}
