package com.lowaltitude.inspection.experience.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ops.VenueOpsError;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsService;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueDetailView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenuePageView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueSessionView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueSessionsGeneratedView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.SessionPlan;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.VenueDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.VenueOpsStatus;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.VenueSessionOpsStatus;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“场馆与设备”的控制器（S3-1）：只给平台运营，操作人取自令牌，业务拒绝按契约给状态码和 details。 */
class OpsVenueControllerTest {

    private static final UUID VENUE = UUID.fromString("b2000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("b4000000-0000-4000-8000-000000000101");
    private static final UUID SESSION = UUID.fromString("b5000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final VenueOpsService.Operator OPERATOR =
            new VenueOpsService.Operator(OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME);

    private final VenueOpsService service = mock(VenueOpsService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new OpsVenueController(service, OpsTestSupport.actors()));

    @Test
    void operatorCreatesWithAKeyAndTheSecondCallIsAReplay() throws Exception {
        String body = """
                {"operatorId":"a1000000-0000-4000-8000-000000000004","name":"东营低空与机器人体验馆","cityCode":"370500",
                 "address":"东营区示例路 66 号","opensAt":"09:30","closesAt":"18:00","closedNote":"周一闭馆",
                 "lateEntryMinutes":10,"infoItems":[{"label":"入口","content":"东门入场"}]}
                """;
        when(service.createVenue(eq(KEY), any(VenueDraft.class), eq(OPERATOR)))
                .thenReturn(new VenueOpsService.Created(detail(), true))
                .thenReturn(new VenueOpsService.Created(detail(), false));

        mockMvc.perform(post("/api/v1/ops/venues").principal(OpsTestSupport.operator())
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.publishMissing[0]").value("coverUrl"));
        mockMvc.perform(post("/api/v1/ops/venues").principal(OpsTestSupport.operator())
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(service, org.mockito.Mockito.times(2)).createVenue(eq(KEY), eq(new VenueDraft(
                UUID.fromString("a1000000-0000-4000-8000-000000000004"), "东营低空与机器人体验馆", "370500", null,
                "东营区示例路 66 号", null, "09:30", "18:00", "周一闭馆", null, 10, null,
                List.of(new com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.InfoItemDraft(
                        "入口", "东门入场")))), eq(OPERATOR));
    }

    @Test
    void listsGeneratesAndChangesSessions() throws Exception {
        when(service.venues("体验", "370500", "PUBLISHED", 2, 10)).thenReturn(new OpsVenuePageView(2, 10, 11, false,
                List.of()));
        when(service.generateSessions(VENUE, PROJECT, new SessionPlan("2026-11-24", "2026-12-07",
                List.of("MONDAY", "SATURDAY"), List.of("10:00", "14:00"), null), OPERATOR))
                .thenReturn(new OpsVenueSessionsGeneratedView(8, 0, 0));
        when(service.stopSale(VENUE, SESSION, 2, OPERATOR)).thenReturn(new OpsVenueSessionView(SESSION, PROJECT,
                OffsetDateTime.parse("2026-11-24T02:00:00Z"), OffsetDateTime.parse("2026-11-24T02:30:00Z"), 8, 3, 2,
                VenueSessionOpsStatus.STOPPED, 3));

        mockMvc.perform(get("/api/v1/ops/venues?keyword=体验&cityCode=370500&status=PUBLISHED&page=2&size=10")
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11));
        mockMvc.perform(post("/api/v1/ops/venues/" + VENUE + "/projects/" + PROJECT + "/sessions/generate")
                        .principal(OpsTestSupport.operator()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromDate\":\"2026-11-24\",\"toDate\":\"2026-12-07\","
                                + "\"weekdays\":[\"MONDAY\",\"SATURDAY\"],\"startTimes\":[\"10:00\",\"14:00\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdCount").value(8));
        mockMvc.perform(post("/api/v1/ops/venues/" + VENUE + "/sessions/" + SESSION + "/stop-sale")
                        .principal(OpsTestSupport.operator()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.activeBookingCount").value(2));
    }

    @Test
    void rejectionsFollowTheContract() throws Exception {
        when(service.publish(VENUE, 3, null, OPERATOR)).thenThrow(OpsException.incomplete(
                VenueOpsError.PUBLISH_INCOMPLETE, List.of("coverUrl", "sessions")));
        when(service.cancelSession(VENUE, SESSION, 1, OPERATOR))
                .thenThrow(new OpsException(VenueOpsError.SESSION_HAS_BOOKINGS));
        when(service.venue(VENUE)).thenThrow(new OpsException(VenueOpsError.NOT_FOUND));

        mockMvc.perform(post("/api/v1/ops/venues/" + VENUE + "/publish").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS_PUBLISH_INCOMPLETE"))
                .andExpect(jsonPath("$.details.missing[0]").value("coverUrl"))
                .andExpect(jsonPath("$.details.missing[1]").value("sessions"));
        mockMvc.perform(post("/api/v1/ops/venues/" + VENUE + "/sessions/" + SESSION + "/cancel")
                        .principal(OpsTestSupport.operator()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_VENUE_SESSION_HAS_BOOKINGS"));
        mockMvc.perform(get("/api/v1/ops/venues/" + VENUE).principal(OpsTestSupport.operator()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        // 新建不带幂等键：参数无效，不调用服务。
        mockMvc.perform(post("/api/v1/ops/venues/" + VENUE + "/devices").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"ROBOT\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherRolesAndDisabledOperatorsAreForbidden() throws Exception {
        for (var who : List.of(OpsTestSupport.support(),
                OpsTestSupport.signedIn(OpsTestSupport.OPERATOR_ID, "PROVIDER_OPERATOR", "LIVE_HOST"),
                OpsTestSupport.signedIn(UUID.randomUUID(), "PLATFORM_OPERATOR"))) {
            mockMvc.perform(get("/api/v1/ops/venues").principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
            mockMvc.perform(put("/api/v1/ops/venues/" + VENUE + "/projects/" + PROJECT + "/status").principal(who)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DISABLED\",\"version\":1}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/ops/venues/" + VENUE + "/changes").principal(who))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    private static OpsVenueDetailView detail() {
        return new OpsVenueDetailView(VENUE, "东营低空与机器人体验馆",
                UUID.fromString("a1000000-0000-4000-8000-000000000004"), "东营低空与机器人体验馆", "370500", "东营市",
                null, "东营区示例路 66 号", null, "09:30", "18:00", "周一闭馆", "09:30–18:00 · 周一闭馆", 30, 10, 0,
                VenueOpsStatus.DRAFT, null, List.of(), List.of(), List.of(), List.of("coverUrl", "projects", "sessions"),
                OffsetDateTime.parse("2026-11-23T02:00:00Z"), 0);
    }
}
