package com.lowaltitude.inspection.activity.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ops.ActivityOpsError;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsService;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“活动与场次”的控制器：只给平台运营，操作人带显示名，业务拒绝按契约给状态码和 details。 */
class OpsActivityControllerTest {

    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-0000000000a1");
    private static final UUID SESSION = UUID.fromString("a3000000-0000-4000-8000-0000000000a1");
    private static final ActivityOpsViews.Operator OPERATOR = new ActivityOpsViews.Operator(OpsTestSupport.OPERATOR_ID,
            OpsTestSupport.OPERATOR_NAME);

    private final ActivityOpsService service = mock(ActivityOpsService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new OpsActivityController(service, OpsTestSupport.actors()));

    @Test
    void operatorCreatesPublishesAndStopsASession() throws Exception {
        when(service.create(any(), eq(OPERATOR))).thenReturn(view());
        when(service.publish(ACTIVITY, 3, OPERATOR)).thenReturn(view());
        when(service.stopSale(ACTIVITY, SESSION, 5, OPERATOR)).thenReturn(view());
        when(service.activities("挑战", "370500", "CONTEST", "DRAFT", 2, 10)).thenReturn(
                new ActivityOpsViews.OpsActivityPageView(2, 10, 11, false, List.of()));

        mockMvc.perform(post("/api/v1/ops/activities").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"CONTEST","title":"挑战赛","organizerId":"a1000000-0000-4000-8000-000000000001",
                                 "cityCode":"370500","venueName":"体验馆","venueAddress":"示例路 1 号",
                                 "priceMode":"PER_PERSON","priceAmountMinor":12000,
                                 "galleryUrls":["https://cdn.example.com/a.jpg"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activityId").value(ACTIVITY.toString()))
                .andExpect(jsonPath("$.publishMissing[0]").value("coverUrl"));
        verify(service).create(eq(new ActivityOpsViews.ActivityDraft("CONTEST", "挑战赛", null, null,
                List.of("https://cdn.example.com/a.jpg"), UUID.fromString("a1000000-0000-4000-8000-000000000001"),
                null, "370500", "体验馆", "示例路 1 号", null, "PER_PERSON", 12000L, null, null, null, null, null,
                null)), eq(OPERATOR));
        mockMvc.perform(post("/api/v1/ops/activities/" + ACTIVITY + "/publish").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":3}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/ops/activities/" + ACTIVITY + "/sessions/" + SESSION + "/stop-sale")
                        .principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":5}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/ops/activities?keyword=挑战&cityCode=370500&type=CONTEST&status=DRAFT&page=2&size=10")
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11));
        mockMvc.perform(delete("/api/v1/ops/activities/" + ACTIVITY + "?version=0").principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        verify(service).delete(ACTIVITY, 0, OPERATOR);
    }

    @Test
    void rejectionsFollowTheContract() throws Exception {
        when(service.publish(ACTIVITY, 1, OPERATOR)).thenThrow(OpsException.incomplete(
                ActivityOpsError.PUBLISH_INCOMPLETE, List.of("coverUrl", "sessions")));
        when(service.deleteSession(ACTIVITY, SESSION, 2, OPERATOR))
                .thenThrow(new OpsException(ActivityOpsError.SESSION_HAS_ENROLLMENTS));
        when(service.activity(ACTIVITY)).thenThrow(new OpsException(ActivityOpsError.NOT_FOUND));

        mockMvc.perform(post("/api/v1/ops/activities/" + ACTIVITY + "/publish").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS_PUBLISH_INCOMPLETE"))
                .andExpect(jsonPath("$.details.missing[1]").value("sessions"));
        mockMvc.perform(delete("/api/v1/ops/activities/" + ACTIVITY + "/sessions/" + SESSION + "?version=2")
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_ACTIVITY_SESSION_HAS_ENROLLMENTS"));
        mockMvc.perform(get("/api/v1/ops/activities/" + ACTIVITY).principal(OpsTestSupport.operator()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/ops/activities/" + ACTIVITY).principal(OpsTestSupport.operator()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void otherRolesAndDisabledOperatorsAreForbidden() throws Exception {
        for (var who : List.of(OpsTestSupport.support(),
                OpsTestSupport.signedIn(OpsTestSupport.OPERATOR_ID, "PROVIDER_OPERATOR", "LIVE_HOST"),
                OpsTestSupport.signedIn(UUID.randomUUID(), "PLATFORM_OPERATOR"))) {
            mockMvc.perform(get("/api/v1/ops/activities").principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
            mockMvc.perform(post("/api/v1/ops/activities/" + ACTIVITY + "/offline").principal(who)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/v1/ops/activities/" + ACTIVITY + "/sessions/" + SESSION).principal(who)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"startsAt\":\"2026-12-01T01:00:00Z\",\"enrollmentClosesAt\":"
                                    + "\"2026-11-30T01:00:00Z\",\"capacity\":10,\"version\":1}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    private static ActivityOpsViews.OpsActivityView view() {
        OffsetDateTime at = OffsetDateTime.parse("2026-11-22T02:00:00Z");
        return new ActivityOpsViews.OpsActivityView(ACTIVITY, ActivityType.CONTEST, "挑战赛", null, null, List.of(),
                new ActivityOpsViews.OpsOrganizationRefView(UUID.fromString("a1000000-0000-4000-8000-000000000001"),
                        "示例青少年科技机构"), null, "370500", "东营市", "体验馆", "示例路 1 号", null,
                ActivityPriceMode.PER_PERSON, 12000L, null, null, null, null, null, null,
                ActivityOpsViews.OpsActivityStatus.DRAFT, null, false, true,
                List.of("coverUrl", "enrollmentNotice", "refundNote", "sessions"), List.of(), at, at, 0);
    }
}
