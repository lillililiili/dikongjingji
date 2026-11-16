package com.lowaltitude.inspection.activity.application;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ActivityCatalogControllerTest {

    private static final UUID ACTIVITY_ID = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("a3000000-0000-4000-8000-000000000101");
    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2026-11-28T01:00:00Z");

    private final ActivityCatalogReadService service = mock(ActivityCatalogReadService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // standaloneSetup 默认的 Jackson 把时间写成数字；这里换成与应用一致的配置（ISO 文本），否则断言不到真实格式。
        mockMvc = MockMvcBuilders.standaloneSetup(new ActivityCatalogController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @Test
    void listPassesEveryFilterToTheServiceAndReturnsTheBarePage() throws Exception {
        when(service.search("370500", "CONTEST", "2026-11-28", true, true, 2, 10))
                .thenReturn(new ActivityPageView(2, 10, 11, false, List.of(card())));

        mockMvc.perform(get("/api/v1/activities")
                        .param("cityCode", " 370500 ")
                        .param("type", "CONTEST")
                        .param("date", "2026-11-28")
                        .param("enrolling", "true")
                        .param("free", "true")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items[0].activityId").value(ACTIVITY_ID.toString()))
                .andExpect(jsonPath("$.items[0].type").value("CONTEST"))
                .andExpect(jsonPath("$.items[0].enrollmentStatus").value("OPEN"))
                .andExpect(jsonPath("$.items[0].price.mode").value("PER_PERSON"))
                .andExpect(jsonPath("$.items[0].price.amountMinor").value(12000))
                .andExpect(jsonPath("$.items[0].price.currency").value("CNY"))
                .andExpect(jsonPath("$.items[0].coverImage").value(nullValue()))
                .andExpect(jsonPath("$.items[0].scheduleNote").value(nullValue()))
                .andExpect(jsonPath("$.items[0].nextSession.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.items[0].nextSession.startsAt").value("2026-11-28T01:00:00Z"))
                .andExpect(jsonPath("$.items[0].nextSession.seatsLeft").value(12));
    }

    @Test
    void listUsesTheContractDefaults() throws Exception {
        when(service.search("370500", null, null, false, false, 1, 20))
                .thenReturn(new ActivityPageView(1, 20, 0, false, List.of()));

        mockMvc.perform(get("/api/v1/activities").param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        verify(service).search("370500", null, null, false, false, 1, 20);
    }

    @Test
    void sessionDatesAreSerializedAsPlainDates() throws Exception {
        when(service.findSessionDates("370500", null, "2026-11-16", "2026-11-30"))
                .thenReturn(new ActivitySessionDatesView(List.of(
                        LocalDate.parse("2026-11-17"),
                        LocalDate.parse("2026-11-28"))));

        mockMvc.perform(get("/api/v1/activities/session-dates")
                        .param("cityCode", "370500")
                        .param("from", "2026-11-16")
                        .param("to", "2026-11-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates[0]").value("2026-11-17"))
                .andExpect(jsonPath("$.dates[1]").value("2026-11-28"));
    }

    @Test
    void detailReturnsSessionsWithTheirComputedStatus() throws Exception {
        when(service.findDetail(ACTIVITY_ID, "370500")).thenReturn(detail());

        mockMvc.perform(get("/api/v1/activities/{activityId}", ACTIVITY_ID)
                        .param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("青少年无人机编程挑战赛（东营站）"))
                .andExpect(jsonPath("$.organizer.name").value("示例青少年科技机构"))
                .andExpect(jsonPath("$.coOrganizer").value(nullValue()))
                .andExpect(jsonPath("$.location.latitude").value(37.4487))
                .andExpect(jsonPath("$.ageRange.minAge").value(8))
                .andExpect(jsonPath("$.waitlistAllowed").value(true))
                .andExpect(jsonPath("$.sessions[0].status").value("FULL_WAITLIST"))
                .andExpect(jsonPath("$.sessions[0].endsAt").value(nullValue()))
                .andExpect(jsonPath("$.sessions[0].enrollmentClosesAt").value("2026-11-27T10:00:00Z"));
    }

    @Test
    void missingOrMalformedArgumentsAreBadRequestsNotServerErrors() throws Exception {
        when(service.search(null, null, null, false, false, 1, 20))
                .thenThrow(new IllegalArgumentException("city code is invalid"));

        mockMvc.perform(get("/api/v1/activities"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/activities").param("cityCode", "370500").param("free", "maybe"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/activities/not-a-uuid").param("cityCode", "370500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void unknownActivityIsNotFound() throws Exception {
        when(service.findDetail(ACTIVITY_ID, "370500")).thenThrow(new ActivityNotFoundException());

        mockMvc.perform(get("/api/v1/activities/{activityId}", ACTIVITY_ID)
                        .param("cityCode", "370500"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    private static ActivityCardView card() {
        return new ActivityCardView(
                ACTIVITY_ID,
                ActivityType.CONTEST,
                "青少年无人机编程挑战赛（东营站）",
                null,
                "东营低空与机器人体验馆",
                null,
                new ActivityPriceView(ActivityPriceMode.PER_PERSON, 12000L, "CNY"),
                new ActivityAgeRangeView(8, 15),
                ActivityEnrollmentStatus.OPEN,
                new ActivityNextSessionView(SESSION_ID, STARTS_AT, 60, 12));
    }

    private static ActivityDetailView detail() {
        return new ActivityDetailView(
                ACTIVITY_ID,
                ActivityType.CONTEST,
                "青少年无人机编程挑战赛（东营站）",
                "图形化编程 · 现场闯关 · 完赛证明",
                null,
                new ActivityPriceView(ActivityPriceMode.PER_PERSON, 12000L, "CNY"),
                new ActivityAgeRangeView(8, 15),
                ActivityEnrollmentStatus.FULL_WAITLIST,
                new ActivityOrganizerView(UUID.fromString("a1000000-0000-4000-8000-000000000001"), "示例青少年科技机构"),
                null,
                new ActivityLocationView("东营低空与机器人体验馆", "东营区示例路 66 号", 37.4487, 118.5823),
                null,
                "8–15 岁，由监护人代报名并到场",
                "陪同家长不用报名",
                "活动开始 48 小时前可免费取消",
                true,
                true,
                List.of(new ActivitySessionView(
                        SESSION_ID,
                        "上午",
                        STARTS_AT,
                        null,
                        STARTS_AT.minusMinutes(30),
                        OffsetDateTime.parse("2026-11-27T10:00:00Z"),
                        60,
                        0,
                        ActivityEnrollmentStatus.FULL_WAITLIST)));
    }
}
