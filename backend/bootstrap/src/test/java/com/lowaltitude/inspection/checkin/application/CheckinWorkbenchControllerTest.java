package com.lowaltitude.inspection.checkin.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.activity.application.ActivityCheckinDesk;
import com.lowaltitude.inspection.activity.application.ActivityCheckinException;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.experience.application.VenueCheckinDesk;
import com.lowaltitude.inspection.experience.application.VenueCheckinException;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 工作台接口：令牌里的操作员和机构、两个模块合在一起、凭证号按前缀分到对应模块、被拦下回 409 带记录、业务拒绝的错误码。
 * 没带令牌时的 401 由安全过滤器给出（AuthenticationSecurityIntegrationTest）；这里的 standaloneSetup 不带过滤器。
 */
class CheckinWorkbenchControllerTest {

    /** 北京时间 2026-11-23 14:05。 */
    private static final Instant NOW = Instant.parse("2026-11-23T06:05:00Z");
    private static final OffsetDateTime AT = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final UUID OPERATOR = UUID.fromString("a1000000-0000-4000-8000-0000000000a4");
    private static final UUID ORG = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID ACTIVITY_SESSION = UUID.fromString("a3000000-0000-4000-8000-000000000401");
    private static final UUID VENUE_SESSION = UUID.fromString("b6000000-0000-4000-8000-000000000001");
    private static final UUID RECORD = UUID.fromString("a7000000-0000-4000-8000-000000000001");

    private final ActivityCheckinDesk activities = mock(ActivityCheckinDesk.class);
    private final VenueCheckinDesk venues = mock(VenueCheckinDesk.class);
    private final TestingAuthenticationToken operator = signedIn("PROVIDER_OPERATOR", ORG);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CheckinWorkbench workbench = new CheckinWorkbench(provider(activities), provider(venues),
                Clock.fixed(NOW, ZoneOffset.UTC));
        mockMvc = MockMvcBuilders.standaloneSetup(new CheckinWorkbenchController(workbench))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @Test
    void onlyAProviderOperatorOfAnOrganizationGetsIn() throws Exception {
        String record = "/api/v1/checkin-workbench/records/ACTIVITY/" + RECORD;
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/v1/checkin-workbench"),
                get("/api/v1/checkin-workbench/records"),
                get(record),
                post("/api/v1/checkin-workbench/verifications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ACT-7K2M-9QXD-4HTP\",\"method\":\"SCAN\"}"),
                post(record + "/resolution").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"BLOCK\"}"))) {
            mockMvc.perform(request.principal(signedIn("CUSTOMER", ORG)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
            mockMvc.perform(request.principal(signedIn("PROVIDER_OPERATOR", null)))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void theWorkbenchMergesBothKindsOfTodayInTimeOrder() throws Exception {
        LocalDate today = LocalDate.of(2026, 11, 23);
        when(activities.verifies(ORG)).thenReturn(true);
        when(venues.verifies(ORG)).thenReturn(true);
        when(activities.sessions(ORG, today)).thenReturn(List.of(new ActivityCheckinDesk.DeskSession(
                ACTIVITY_SESSION, "无人机开放体验日", "体验馆", "下午场", AT.plusHours(1), null,
                AT, AT.plusHours(1), ActivityCheckinDesk.Phase.OPEN, 5, 1)));
        when(venues.sessions(ORG, today)).thenReturn(List.of(new VenueCheckinDesk.DeskSession(
                VENUE_SESSION, "机器人避障闯关", "东营低空与机器人体验馆", AT.minusMinutes(5), AT.plusMinutes(55),
                AT.minusMinutes(35), AT.plusMinutes(55), VenueCheckinDesk.Phase.OPEN, 8, 3)));

        mockMvc.perform(get("/api/v1/checkin-workbench").principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-23"))
                .andExpect(jsonPath("$.kinds[0]").value("ACTIVITY"))
                .andExpect(jsonPath("$.kinds[1]").value("VENUE"))
                .andExpect(jsonPath("$.sessions[0].kind").value("VENUE"))
                .andExpect(jsonPath("$.sessions[0].label").doesNotExist())
                .andExpect(jsonPath("$.sessions[0].expectedPeople").value(8))
                .andExpect(jsonPath("$.sessions[1].kind").value("ACTIVITY"))
                .andExpect(jsonPath("$.sessions[1].label").value("下午场"))
                .andExpect(jsonPath("$.sessions[1].phase").value("OPEN"));
    }

    @Test
    void anOrganizationWithNeitherKindHasAnEmptyWorkbench() throws Exception {
        mockMvc.perform(get("/api/v1/checkin-workbench").param("date", "2026-11-20").principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-20"))
                .andExpect(jsonPath("$.kinds").isEmpty())
                .andExpect(jsonPath("$.sessions").isEmpty());
        verify(activities, never()).sessions(any(), any());
    }

    @Test
    void aCodeGoesToItsModuleAndASuccessIsReturned() throws Exception {
        when(venues.sessionRef(ORG, VENUE_SESSION)).thenReturn(Optional.of(
                new VenueCheckinDesk.SessionRef(VENUE_SESSION, "机器人避障闯关", AT.minusMinutes(5))));
        when(venues.verify(eq(OPERATOR), eq(ORG), eq("VEN-7K2M-9QXD-0314"), eq(VenueCheckinDesk.Method.MANUAL),
                eq(new VenueCheckinDesk.Current(VenueCheckinDesk.Kind.VENUE, VENUE_SESSION))))
                .thenReturn(venueEntry(VenueCheckinDesk.Outcome.CHECKED_IN));

        mockMvc.perform(post("/api/v1/checkin-workbench/verifications").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\" ven-7k2m-9qxd-0314 \",\"method\":\"MANUAL\",\"sessionKind\":\"VENUE\",\"sessionId\":\""
                                + VENUE_SESSION + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("VENUE"))
                .andExpect(jsonPath("$.outcome").value("CHECKED_IN"))
                .andExpect(jsonPath("$.people").value(2))
                .andExpect(jsonPath("$.amountMinor").value(7800))
                .andExpect(jsonPath("$.currentSession.title").value("机器人避障闯关"));
        verify(activities, never()).verify(any(), any(), any(), any(), any());
    }

    @Test
    void aRejectionIsAConflictCarryingTheStoredRecordWithTheOtherKindsSession() throws Exception {
        when(venues.sessionRef(ORG, VENUE_SESSION)).thenReturn(Optional.of(
                new VenueCheckinDesk.SessionRef(VENUE_SESSION, "机器人避障闯关", AT.minusMinutes(5))));
        when(activities.verify(eq(OPERATOR), eq(ORG), eq("ACT-7K2M-9QXD-4HTP"), eq(ActivityCheckinDesk.Method.SCAN),
                eq(new ActivityCheckinDesk.Current(ActivityCheckinDesk.Kind.VENUE, VENUE_SESSION))))
                .thenReturn(new ActivityCheckinDesk.Entry(RECORD, ActivityCheckinDesk.Outcome.SESSION_MISMATCH,
                        ActivityCheckinDesk.Method.SCAN, AT, "4HTP", "孙**", 1, 0L,
                        new ActivityCheckinDesk.SessionRef(ACTIVITY_SESSION, "无人机开放体验日", null, AT.plusHours(1)),
                        ActivityCheckinDesk.Kind.VENUE, VENUE_SESSION, null, null,
                        List.of(ActivityCheckinDesk.Action.REJECT), null));

        mockMvc.perform(post("/api/v1/checkin-workbench/verifications").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ACT-7K2M-9QXD-4HTP\",\"method\":\"SCAN\",\"sessionKind\":\"VENUE\",\"sessionId\":\""
                                + VENUE_SESSION + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKIN_SESSION_MISMATCH"))
                .andExpect(jsonPath("$.details.record.recordId").value(RECORD.toString()))
                .andExpect(jsonPath("$.details.record.kind").value("ACTIVITY"))
                .andExpect(jsonPath("$.details.record.ticketSession.title").value("无人机开放体验日"))
                .andExpect(jsonPath("$.details.record.currentSession.kind").value("VENUE"))
                .andExpect(jsonPath("$.details.record.currentSession.title").value("机器人避障闯关"))
                .andExpect(jsonPath("$.details.record.allowedActions[0]").value("REJECT"));
    }

    @Test
    void aSessionOfAnotherOrganizationOrAnUnknownPrefixIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/checkin-workbench/verifications").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ACT-7K2M-9QXD-4HTP\",\"method\":\"SCAN\",\"sessionKind\":\"ACTIVITY\",\"sessionId\":\""
                                + ACTIVITY_SESSION + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/checkin-workbench/verifications").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ORD-7K2M-9QXD-4HTP\",\"method\":\"SCAN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/checkin-workbench/verifications").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ACT-7K2M-9QXD-4HTP\",\"method\":\"SCAN\",\"sessionKind\":\"ACTIVITY\"}"))
                .andExpect(status().isBadRequest());
        verify(activities, never()).verify(any(), any(), any(), any(), any());
    }

    @Test
    void moduleRejectionsUseTheWorkbenchCodes() throws Exception {
        String url = "/api/v1/checkin-workbench/records/ACTIVITY/" + RECORD + "/resolution";
        String body = "{\"action\":\"RELEASE\",\"identityChecked\":true,\"note\":\"本场有余位\"}";
        Object[][] cases = {
                {ActivityCheckinException.Reason.SEATS_NOT_ENOUGH, 409, "CHECKIN_SEATS_NOT_ENOUGH"},
                {ActivityCheckinException.Reason.RELEASE_NOT_ALLOWED, 409, "CHECKIN_RELEASE_NOT_ALLOWED"},
                {ActivityCheckinException.Reason.ALREADY_RESOLVED, 409, "CHECKIN_ALREADY_RESOLVED"},
                {ActivityCheckinException.Reason.ACTION_NOT_ALLOWED, 409, "CHECKIN_ACTION_NOT_ALLOWED"},
                {ActivityCheckinException.Reason.NOT_FOUND, 404, "COMMON_NOT_FOUND"},
        };
        for (Object[] c : cases) {
            doThrow(new ActivityCheckinException((ActivityCheckinException.Reason) c[0]))
                    .when(activities).resolve(OPERATOR, ORG, RECORD, ActivityCheckinDesk.Action.RELEASE, true, "本场有余位");
            mockMvc.perform(post(url).principal(operator).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().is((int) c[1]))
                    .andExpect(jsonPath("$.code").value((String) c[2]));
        }
        when(venues.verify(any(), any(), any(), any(), any()))
                .thenThrow(new VenueCheckinException(VenueCheckinException.Reason.NOT_OPEN));
        mockMvc.perform(post("/api/v1/checkin-workbench/verifications").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"VEN-7K2M-9QXD-0314\",\"method\":\"SCAN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHECKIN_NOT_OPEN"));
    }

    @Test
    void resolvingPassesTheIdentityCheckAndRecordsAreTheOperatorsOwn() throws Exception {
        when(venues.resolve(OPERATOR, ORG, RECORD, VenueCheckinDesk.Action.BLOCK, false, null))
                .thenReturn(venueEntry(VenueCheckinDesk.Outcome.REPEATED));
        mockMvc.perform(post("/api/v1/checkin-workbench/records/VENUE/" + RECORD + "/resolution").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"BLOCK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("REPEATED"));

        when(activities.records(OPERATOR, ORG, LocalDate.of(2026, 11, 23))).thenReturn(List.of());
        when(venues.records(OPERATOR, ORG, LocalDate.of(2026, 11, 23)))
                .thenReturn(List.of(venueEntry(VenueCheckinDesk.Outcome.CHECKED_IN)));
        mockMvc.perform(get("/api/v1/checkin-workbench/records").principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].kind").value("VENUE"))
                .andExpect(jsonPath("$.items[0].holderName").value("赵**"));

        mockMvc.perform(get("/api/v1/checkin-workbench/records/SOMETHING/" + RECORD).principal(operator))
                .andExpect(status().isBadRequest());
    }

    private static VenueCheckinDesk.Entry venueEntry(VenueCheckinDesk.Outcome outcome) {
        return new VenueCheckinDesk.Entry(RECORD, outcome, VenueCheckinDesk.Method.MANUAL, AT, "0314", "赵**", 2, 7800L,
                new VenueCheckinDesk.SessionRef(VENUE_SESSION, "机器人避障闯关", AT.minusMinutes(5)),
                VenueCheckinDesk.Kind.VENUE, VENUE_SESSION,
                new VenueCheckinDesk.SessionRef(VENUE_SESSION, "机器人避障闯关", AT.minusMinutes(5)),
                outcome == VenueCheckinDesk.Outcome.REPEATED ? AT.minusMinutes(3) : null, List.of(), null);
    }

    private static TestingAuthenticationToken signedIn(String role, UUID organizationId) {
        return new TestingAuthenticationToken(
                new CurrentSubject(OPERATOR, java.util.Set.of(role), organizationId, java.util.Set.of(), 0), null);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
