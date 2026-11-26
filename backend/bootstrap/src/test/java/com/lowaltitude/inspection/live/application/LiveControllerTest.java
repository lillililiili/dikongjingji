package com.lowaltitude.inspection.live.application;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.live.application.LiveException.Error;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 直播的三个控制器（M20）：从令牌里取账号、角色和机构，把请求转成服务的参数，把结果和业务拒绝转成契约里的状态码。
 * 没带令牌时的 401 和公开路径由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里检查。
 */
class LiveControllerTest {

    private static final UUID VIEWER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VIEWER_ORG = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID HOST_ORG = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID OPERATOR = UUID.fromString("c1000000-0000-4000-8000-0000000000a1");
    private static final UUID AGENT = UUID.fromString("90000000-0000-4000-8000-0000000000a1");
    private static final UUID PLATFORM_ORG = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID ROOM = UUID.fromString("f4000000-0000-4000-8000-000000000001");
    private static final UUID COMMENT = UUID.fromString("f4100000-0000-4000-8000-000000000001");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000401");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-20T11:00:00Z");

    /** 每一种业务拒绝对外的状态码和错误码（GlobalExceptionHandler 与契约 1.0.0）。 */
    private static final Map<Error, Rejection> REJECTIONS = Map.of(
            Error.NOT_FOUND, new Rejection(404, "COMMON_NOT_FOUND"),
            Error.FORBIDDEN, new Rejection(403, "COMMON_FORBIDDEN"),
            Error.IDEMPOTENCY_CONFLICT, new Rejection(409, "LIVE_IDEMPOTENCY_KEY_REUSED"),
            Error.NOT_SCHEDULED, new Rejection(409, "LIVE_ROOM_NOT_SCHEDULED"),
            Error.NOT_LIVE, new Rejection(409, "LIVE_ROOM_NOT_LIVE"),
            Error.ENDED, new Rejection(409, "LIVE_ROOM_ENDED"),
            Error.COMMENT_BLOCKED, new Rejection(409, "LIVE_COMMENT_BLOCKED"),
            Error.COMMENT_TOO_FAST, new Rejection(429, "LIVE_COMMENT_TOO_FAST"),
            Error.STREAM_UNAVAILABLE, new Rejection(503, "LIVE_STREAM_UNAVAILABLE"),
            Error.CALLBACK_REJECTED, new Rejection(401, "LIVE_CALLBACK_REJECTED"));

    private final LiveService service = mock(LiveService.class);
    private final TestingAuthenticationToken viewer = signedIn(VIEWER, VIEWER_ORG, "CUSTOMER");
    private final TestingAuthenticationToken host = signedIn(OPERATOR, HOST_ORG, "LIVE_HOST");
    private final TestingAuthenticationToken agent = signedIn(AGENT, PLATFORM_ORG, "PLATFORM_SUPPORT");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LiveController(service), new LiveHostController(service),
                        new LiveStreamCallbackController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(), new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    // ---- 谁能调 ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("viewerRoutes")
    void viewerRoutesNeedTheCustomerRole(String route, MockHttpServletRequestBuilder request) throws Exception {
        for (TestingAuthenticationToken who : List.of(host, agent)) {
            mockMvc.perform(request.principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostRoutes")
    void hostRoutesNeedTheLiveHostRoleOfAnOrganization(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        for (TestingAuthenticationToken who : List.of(viewer, agent, signedIn(OPERATOR, null, "LIVE_HOST"),
                signedIn(OPERATOR, HOST_ORG, "PROVIDER_OPERATOR"))) {
            mockMvc.perform(request.principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("platformRoutes")
    void platformRoutesNeedThePlatformSupportRole(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        for (TestingAuthenticationToken who : List.of(viewer, host, signedIn(AGENT, PLATFORM_ORG, "PLATFORM_OPERATOR"))) {
            mockMvc.perform(request.principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(service);
    }

    // ---- 公开 ----

    @Test
    void publicReadsPassTheQueryAndAnswerTheContractShapes() throws Exception {
        when(service.rooms("370500", "LIVE", 2, 5)).thenReturn(new LiveRoomPageView(2, 5, 6, false, List.of(card())));
        when(service.room(ROOM, "370500")).thenReturn(room());
        when(service.comments(ROOM, 4L, 10)).thenReturn(new LiveCommentPageView(List.of(comment()), 5, List.of(3L)));
        when(service.recordView(ROOM, KEY)).thenReturn(new LiveViewCountView(12401, true));

        mockMvc.perform(get("/api/v1/live/rooms?cityCode=370500&status=LIVE&page=2&size=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.items[0].length()").value(14))
                .andExpect(jsonPath("$.items[0].status").value("LIVE"))
                .andExpect(jsonPath("$.items[0].host.name").value("鹰眼智能巡检"))
                .andExpect(jsonPath("$.items[0].currentItem.type").value("INSPECTION_SERVICE"))
                .andExpect(jsonPath("$.items[0].endedAt").isEmpty())
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-11-20T10:18:00Z"));
        mockMvc.perform(get("/api/v1/live/rooms/" + ROOM + "?cityCode=370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(21))
                .andExpect(jsonPath("$.playUrl").value("/api/v1/dev-media/videos/preview/live-night-patrol.mp4"))
                .andExpect(jsonPath("$.topics[0]").value("夜间路线怎么定"))
                .andExpect(jsonPath("$.items[1].refId").value(PRODUCT.toString()))
                .andExpect(jsonPath("$.currentItemSeq").value(1));
        mockMvc.perform(get("/api/v1/live/rooms/" + ROOM + "/comments?since=4&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cursor").value(5))
                .andExpect(jsonPath("$.hiddenSeqs[0]").value(3))
                .andExpect(jsonPath("$.items[0].length()").value(6))
                .andExpect(jsonPath("$.items[0].authorName").value("王**"));
        mockMvc.perform(post("/api/v1/live/rooms/" + ROOM + "/views").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"" + KEY + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerCount").value(12401))
                .andExpect(jsonPath("$.counted").value(true));
        mockMvc.perform(get("/api/v1/live/rooms?cityCode=370500&page=x")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/live/rooms/not-a-uuid?cityCode=370500")).andExpect(status().isBadRequest());
    }

    @Test
    void theStreamCallbackHandsTheRawBodyAndTheSignatureHeadersToTheService() throws Exception {
        String body = "{\"eventId\":\"e1\",\"streamId\":\"s1\",\"type\":\"STARTED\",\"occurredAt\":\"2026-11-20T11:00:00Z\"}";

        mockMvc.perform(post("/api/v1/live/stream-callbacks").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Live-Timestamp", "1763636400").header("X-Live-Signature", "abc").content(body))
                .andExpect(status().isNoContent());
        verify(service).receiveStreamCallback(Map.of("x-live-timestamp", "1763636400", "x-live-signature", "abc"),
                body);

        org.mockito.Mockito.doThrow(new LiveException(Error.CALLBACK_REJECTED)).when(service)
                .receiveStreamCallback(Map.of(), "{}");
        mockMvc.perform(post("/api/v1/live/stream-callbacks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("LIVE_CALLBACK_REJECTED"));
    }

    // ---- 观众 ----

    @Test
    void viewerCommandsUseTheAccountOfTheToken() throws Exception {
        LiveViewerStateView state = new LiveViewerStateView(ROOM, true, false);
        when(service.viewerState(VIEWER, ROOM)).thenReturn(state);
        when(service.remind(VIEWER, ROOM)).thenReturn(state);
        when(service.cancelReminder(VIEWER, ROOM)).thenReturn(new LiveViewerStateView(ROOM, false, false));
        when(service.like(VIEWER, ROOM, 7)).thenReturn(new LiveLikeView(7, 23607));
        when(service.like(VIEWER, ROOM, 0)).thenThrow(new IllegalArgumentException("count is invalid"));
        when(service.report(VIEWER, ROOM, COMMENT)).thenReturn(new LiveReportView(COMMENT, true));
        when(service.follow(VIEWER, HOST_ORG)).thenReturn(new LiveFollowView(HOST_ORG, true));
        when(service.unfollow(VIEWER, HOST_ORG)).thenReturn(new LiveFollowView(HOST_ORG, false));
        String room = "/api/v1/live/rooms/" + ROOM;

        mockMvc.perform(get(room + "/me").principal(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$.reminded").value(true));
        mockMvc.perform(put(room + "/reminder").principal(viewer)).andExpect(status().isOk());
        mockMvc.perform(delete(room + "/reminder").principal(viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.reminded").value(false));
        mockMvc.perform(post(room + "/likes").principal(viewer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(7))
                .andExpect(jsonPath("$.likeCount").value(23607));
        mockMvc.perform(post(room + "/likes").principal(viewer).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post(room + "/comments/" + COMMENT + "/report").principal(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reported").value(true));
        mockMvc.perform(put("/api/v1/live/hosts/" + HOST_ORG + "/follow").principal(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.following").value(true));
        mockMvc.perform(delete("/api/v1/live/hosts/" + HOST_ORG + "/follow").principal(viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.following").value(false));
    }

    @Test
    void sendingACommentAnswers201ThenReplays200AndNeedsTheKey() throws Exception {
        when(service.comment(VIEWER, ROOM, KEY, "热成像能看清人吗"))
                .thenReturn(new LiveCommentSent(comment(), true))
                .thenReturn(new LiveCommentSent(comment(), false));
        String route = "/api/v1/live/rooms/" + ROOM + "/comments";
        String body = "{\"text\":\"热成像能看清人吗\"}";

        mockMvc.perform(post(route).principal(viewer).header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seq").value(6))
                .andExpect(jsonPath("$.side").value("VIEWER"));
        mockMvc.perform(post(route).principal(viewer).header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post(route).principal(viewer).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    // ---- 主办方、平台客服 ----

    @Test
    void hostCommandsUseTheOrganizationOfTheToken() throws Exception {
        LiveHostRoomView hostRoom = new LiveHostRoomView(room(), "rtmp://127.0.0.1:1935/live/fake-1?key=ab");
        LiveRoomDraft draft = new LiveRoomDraft("编程机器人入门", null, null, "370500",
                OffsetDateTime.parse("2026-11-22T11:30:00Z"), "李老师", "课程讲师", List.of("拖积木写第一段程序"));
        when(service.createRoom(HOST_ORG, KEY, draft))
                .thenReturn(new LiveHostRoomCreated(hostRoom, true))
                .thenReturn(new LiveHostRoomCreated(hostRoom, false));
        when(service.hostRooms(HOST_ORG, null, 1, 10)).thenReturn(new LiveHostRoomPageView(1, 10, 1, false, List.of(hostRoom)));
        when(service.hostRooms(HOST_ORG, "LIVE", 2, 5)).thenReturn(new LiveHostRoomPageView(2, 5, 0, false, List.of()));
        when(service.hostRoom(HOST_ORG, ROOM)).thenReturn(hostRoom);
        when(service.updateRoom(HOST_ORG, ROOM, draft)).thenReturn(hostRoom);
        when(service.setReplay(HOST_ORG, ROOM, "https://live.example.com/r", "示例直播平台")).thenReturn(hostRoom);
        when(service.setItems(HOST_ORG, ROOM, List.of(new LiveItemDraft("PRODUCT", PRODUCT, "巡检机器狗")), 1))
                .thenReturn(hostRoom);
        when(service.setCurrentItem(HOST_ORG, ROOM, null)).thenReturn(hostRoom);
        when(service.hostComment(HOST_ORG, OPERATOR, ROOM, KEY, "一圈 40 分钟"))
                .thenReturn(new LiveCommentSent(comment(), true));
        when(service.hostHide(HOST_ORG, ROOM, COMMENT)).thenReturn(new LiveModerationView(COMMENT, 6, NOW));
        when(service.platformHide(ROOM, COMMENT)).thenReturn(new LiveModerationView(COMMENT, 6, NOW));
        when(service.platformEnd(ROOM)).thenReturn(room());
        String rooms = "/api/v1/live-host/rooms";
        String draftBody = """
                {"title":"编程机器人入门","cityCode":"370500","scheduledAt":"2026-11-22T19:30:00+08:00",
                 "speakerName":"李老师","speakerTitle":"课程讲师","topics":["拖积木写第一段程序"]}
                """;

        mockMvc.perform(post(rooms).principal(host).header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(draftBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.pushUrl").value("rtmp://127.0.0.1:1935/live/fake-1?key=ab"))
                .andExpect(jsonPath("$.room.roomId").value(ROOM.toString()));
        mockMvc.perform(post(rooms).principal(host).header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(draftBody))
                .andExpect(status().isOk());
        mockMvc.perform(get(rooms).principal(host)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        // 1.1.0：工作台按状态筛。
        mockMvc.perform(get(rooms).principal(host).param("status", "LIVE").param("page", "2").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get(rooms + "/" + ROOM).principal(host)).andExpect(status().isOk());
        mockMvc.perform(put(rooms + "/" + ROOM).principal(host).contentType(MediaType.APPLICATION_JSON)
                .content(draftBody)).andExpect(status().isOk());
        mockMvc.perform(put(rooms + "/" + ROOM + "/replay").principal(host).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"replayUrl\":\"https://live.example.com/r\",\"replayPlatformName\":\"示例直播平台\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(rooms + "/" + ROOM + "/items").principal(host).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"type\":\"PRODUCT\",\"refId\":\"" + PRODUCT
                                + "\",\"title\":\"巡检机器狗\"}],\"currentSeq\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(put(rooms + "/" + ROOM + "/current-item").principal(host).contentType(MediaType.APPLICATION_JSON)
                .content("{\"seq\":null}")).andExpect(status().isOk());
        mockMvc.perform(post(rooms + "/" + ROOM + "/comments").principal(host).header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"一圈 40 分钟\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post(rooms + "/" + ROOM + "/comments/" + COMMENT + "/hide").principal(host))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seq").value(6));
        mockMvc.perform(post("/api/v1/platform/live/rooms/" + ROOM + "/comments/" + COMMENT + "/hide").principal(agent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hiddenAt").value("2026-11-20T11:00:00Z"));
        mockMvc.perform(post("/api/v1/platform/live/rooms/" + ROOM + "/end").principal(agent))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roomId").value(ROOM.toString()));
    }

    @ParameterizedTest
    @EnumSource(Error.class)
    void everyBusinessRejectionHasItsStatusAndCode(Error error) throws Exception {
        when(service.comment(eq(VIEWER), eq(ROOM), eq(KEY), any())).thenThrow(new LiveException(error));
        Rejection expected = REJECTIONS.get(error);

        mockMvc.perform(post("/api/v1/live/rooms/" + ROOM + "/comments").principal(viewer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"在吗\"}"))
                .andExpect(status().is(expected.status()))
                .andExpect(jsonPath("$.code").value(expected.code()));
    }

    // ---- 小工具 ----

    private static Stream<Arguments> viewerRoutes() {
        String room = "/api/v1/live/rooms/" + ROOM;
        return Stream.of(
                Arguments.of("GET me", get(room + "/me")),
                Arguments.of("PUT reminder", put(room + "/reminder")),
                Arguments.of("DELETE reminder", delete(room + "/reminder")),
                Arguments.of("POST comments", post(room + "/comments").header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"在吗\"}")),
                Arguments.of("POST likes", post(room + "/likes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":1}")),
                Arguments.of("POST report", post(room + "/comments/" + COMMENT + "/report")),
                Arguments.of("PUT follow", put("/api/v1/live/hosts/" + HOST_ORG + "/follow")),
                Arguments.of("DELETE follow", delete("/api/v1/live/hosts/" + HOST_ORG + "/follow")));
    }

    private static Stream<Arguments> hostRoutes() {
        String rooms = "/api/v1/live-host/rooms";
        String room = rooms + "/" + ROOM;
        String draft = "{\"title\":\"直播\",\"scheduledAt\":\"2026-11-22T11:30:00Z\"}";
        return Stream.of(
                Arguments.of("GET rooms", get(rooms)),
                Arguments.of("POST rooms", post(rooms).header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(draft)),
                Arguments.of("GET room", get(room)),
                Arguments.of("PUT room", put(room).contentType(MediaType.APPLICATION_JSON).content(draft)),
                Arguments.of("PUT replay", put(room + "/replay").contentType(MediaType.APPLICATION_JSON).content("{}")),
                Arguments.of("PUT items", put(room + "/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}")),
                Arguments.of("PUT current", put(room + "/current-item").contentType(MediaType.APPLICATION_JSON)
                        .content("{}")),
                Arguments.of("POST comments", post(room + "/comments").header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"在\"}")),
                Arguments.of("POST hide", post(room + "/comments/" + COMMENT + "/hide")));
    }

    private static Stream<Arguments> platformRoutes() {
        String room = "/api/v1/platform/live/rooms/" + ROOM;
        return Stream.of(
                Arguments.of("POST hide", post(room + "/comments/" + COMMENT + "/hide")),
                Arguments.of("POST end", post(room + "/end")));
    }

    private static LiveRoomCardView card() {
        return new LiveRoomCardView(ROOM, "机器狗园区夜巡演示", "看一台机器狗怎样走完园区夜间路线", null,
                new LiveHostView(UUID.fromString("f2000000-0000-4000-8000-000000000001"), "鹰眼智能巡检"), "370500",
                LiveRoomStatus.LIVE, NOW.minusMinutes(47), NOW.minusMinutes(42), null, 12400, 23600,
                new LiveItemView(1, LiveItemType.INSPECTION_SERVICE,
                        UUID.fromString("77777777-7777-7777-7777-777777777777"), "园区安防巡检"), false);
    }

    private static LiveRoomView room() {
        return new LiveRoomView(ROOM, "机器狗园区夜巡演示", "看一台机器狗怎样走完园区夜间路线", null,
                new LiveHostView(UUID.fromString("f2000000-0000-4000-8000-000000000001"), "鹰眼智能巡检"), "370500",
                LiveRoomStatus.LIVE, NOW.minusMinutes(47), NOW.minusMinutes(42), null, 12400, 23600, "王工", "项目负责人",
                List.of("夜间路线怎么定"), List.of(
                        new LiveItemView(1, LiveItemType.INSPECTION_SERVICE,
                                UUID.fromString("77777777-7777-7777-7777-777777777777"), "园区安防巡检"),
                        new LiveItemView(2, LiveItemType.PRODUCT, PRODUCT, "巡检机器狗")),
                1, null, null, "/api/v1/dev-media/videos/preview/live-night-patrol.mp4", false);
    }

    private static LiveCommentView comment() {
        return new LiveCommentView(COMMENT, 6, LiveCommentSide.VIEWER, "王**", "热成像能看清人吗", NOW);
    }

    private static TestingAuthenticationToken signedIn(UUID subjectId, UUID organizationId, String role) {
        return new TestingAuthenticationToken(
                new CurrentSubject(subjectId, Set.of(role), organizationId, Set.of(), 0), null);
    }

    private record Rejection(int status, String code) {
    }
}
