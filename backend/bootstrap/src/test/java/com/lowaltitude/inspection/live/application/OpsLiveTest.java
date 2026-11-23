package com.lowaltitude.inspection.live.application;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.OrganizationOnboardingService;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“直播间”（契约 openapi-ops-live.yaml 1.0.0）：契约逐项对照、只给平台运营、主办机构列表的拼法、业务拒绝。 */
class OpsLiveTest {

    private static final UUID ROOM = UUID.fromString("f4000000-0000-4000-8000-000000000001");
    private static final UUID HOST = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-23T02:00:00Z");

    private final LiveOpsService service = mock(LiveOpsService.class);
    private final OrganizationOnboardingService organizations = mock(OrganizationOnboardingService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(
            new OpsLiveController(service, organizations, OpsTestSupport.actors()));

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-live.yaml", OpsLiveController.class)
            .response("OpsLiveHost", OpsLiveHostView.class)
            .response("OpsLiveRoom", LiveOpsService.OpsLiveRoomView.class)
            .response("OpsLiveRoomPage", LiveOpsService.OpsLiveRoomPageView.class)
            .response("OpsChange", OpsChangeView.class)
            .request("OpsLiveEndRequest", OpsLiveController.EndRequest.class, "note", "version")
            .request("OpsLiveReplayRequest", OpsLiveController.ReplayRequest.class,
                    "replayUrl", "replayPlatformName", "version")
            .enumeration("OpsLiveRoomStatus", LiveRoomStatus.class)
            .other("ApiError", "OpsLiveErrorCode");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.0.0");
        checker.verifyOpsErrors("OpsLiveErrorCode", LiveOpsService.Error.values());
        assertThat(checker.text()).contains("maximum: " + LiveOpsService.MAX_PAGE_SIZE)
                .contains("maxLength: " + LiveOpsService.MAX_NOTE)
                .contains("maxItems: " + LiveOpsService.CHANGE_LIMIT)
                .contains("maxItems: " + OrganizationOnboardingService.MAX_LIVE_HOSTS);
    }

    @Test
    void hostsJoinTheIdentityListWithRoomCounts() throws Exception {
        UUID other = UUID.fromString("c1000000-0000-4000-8000-000000000002");
        when(organizations.liveHostOrganizations()).thenReturn(List.of(
                new OrganizationOnboardingService.LiveHostOrganizationView(HOST, "东营示例主办", "370500", 2),
                new OrganizationOnboardingService.LiveHostOrganizationView(other, "济南示例主办", null, 1)));
        when(service.roomCounts(List.of(HOST, other))).thenReturn(Map.of(
                HOST, new LiveOpsService.HostRoomCounts(3, 1, 1), other, new LiveOpsService.HostRoomCounts(0, 0, 0)));

        mockMvc.perform(get("/api/v1/ops/live/hosts").principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].organizationName").value("东营示例主办"))
                .andExpect(jsonPath("$[0].hostAccountCount").value(2))
                .andExpect(jsonPath("$[0].roomCount").value(3))
                .andExpect(jsonPath("$[0].liveRoomCount").value(1))
                .andExpect(jsonPath("$[1].cityCode").isEmpty())
                .andExpect(jsonPath("$[1].roomCount").value(0));
    }

    @Test
    void operatorEndsAndSetsReplayWithItsOwnName() throws Exception {
        when(service.forceEnd(ROOM, "画面违规", 4, OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME))
                .thenReturn(room("ENDED", 5));
        when(service.setReplay(ROOM, null, null, 5, OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME))
                .thenReturn(room("ENDED", 6));
        when(service.rooms(null, "LIVE", null, HOST, 1, 20)).thenReturn(
                new LiveOpsService.OpsLiveRoomPageView(1, 20, 1, false, List.of(room("LIVE", 0))));

        mockMvc.perform(post("/api/v1/ops/live/rooms/" + ROOM + "/end").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"画面违规\",\"version\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.endReason").value("PLATFORM"))
                .andExpect(jsonPath("$.version").value(5));
        mockMvc.perform(put("/api/v1/ops/live/rooms/" + ROOM + "/replay").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replayUrl\":null,\"replayPlatformName\":null,\"version\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(6));
        mockMvc.perform(get("/api/v1/ops/live/rooms?status=LIVE&hostOrganizationId=" + HOST)
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].hostOrganizationId").value(HOST.toString()));
    }

    @Test
    void rejectionsFollowTheContract() throws Exception {
        when(service.forceEnd(ROOM, "x", 1, OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME))
                .thenThrow(new OpsException(LiveOpsService.Error.ROOM_ENDED));
        when(service.setReplay(ROOM, "https://replay.example.com/1", "示例", 1, OpsTestSupport.OPERATOR_ID,
                OpsTestSupport.OPERATOR_NAME)).thenThrow(new OpsException(LiveOpsService.Error.ROOM_NOT_ENDED));
        when(service.changes(ROOM)).thenThrow(new OpsException(LiveOpsService.Error.NOT_FOUND));

        mockMvc.perform(post("/api/v1/ops/live/rooms/" + ROOM + "/end").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_LIVE_ROOM_ENDED"));
        mockMvc.perform(put("/api/v1/ops/live/rooms/" + ROOM + "/replay").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content(
                                "{\"replayUrl\":\"https://replay.example.com/1\",\"replayPlatformName\":\"示例\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_LIVE_ROOM_NOT_ENDED"));
        mockMvc.perform(get("/api/v1/ops/live/rooms/" + ROOM + "/changes").principal(OpsTestSupport.operator()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    void otherRolesAndDisabledOperatorsAreForbidden() throws Exception {
        for (var who : List.of(OpsTestSupport.support(),
                OpsTestSupport.signedIn(OpsTestSupport.OPERATOR_ID, "PROVIDER_OPERATOR", "LIVE_HOST"),
                OpsTestSupport.signedIn(UUID.randomUUID(), "PLATFORM_OPERATOR"))) {
            mockMvc.perform(get("/api/v1/ops/live/hosts").principal(who)).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/ops/live/rooms").principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
            mockMvc.perform(post("/api/v1/ops/live/rooms/" + ROOM + "/end").principal(who)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\",\"version\":1}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service, organizations);
        mockMvc.perform(get("/api/v1/ops/live/rooms").principal(OpsTestSupport.operator())).andExpect(status().isOk());
        verify(service).rooms(null, null, null, null, 1, 20);
    }

    private static LiveOpsService.OpsLiveRoomView room(String status, int version) {
        boolean ended = "ENDED".equals(status);
        return new LiveOpsService.OpsLiveRoomView(ROOM, HOST, "东营示例主办", "黄河口飞行讲解", null, null, "370500",
                AT.minusHours(1), null, status, AT.minusMinutes(50), ended ? AT : null, ended ? "PLATFORM" : null,
                false, null, null, 12, 30, 4, version, AT.minusDays(1));
    }
}
