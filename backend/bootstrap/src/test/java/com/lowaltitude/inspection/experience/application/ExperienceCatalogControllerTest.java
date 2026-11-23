package com.lowaltitude.inspection.experience.application;

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

class ExperienceCatalogControllerTest {

    private static final UUID VENUE_ID = UUID.fromString("b2000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT_ID = UUID.fromString("b4000000-0000-4000-8000-000000000101");

    private final ExperienceCatalogReadService service = mock(ExperienceCatalogReadService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 与应用一致的 JSON 配置：时间写成 ISO 文本。
        mockMvc = MockMvcBuilders.standaloneSetup(new ExperienceCatalogController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @Test
    void experiencesUseTheContractDefaultsAndReturnTheBarePage() throws Exception {
        when(service.listExperienceItems(null, 1, 20)).thenReturn(new ExperienceItemPageView(
                1, 20, 1, false,
                List.of(new ExperienceItemView(
                        UUID.randomUUID(), ExperienceType.SIMULATION, "机器人避障模拟", null, null, false))));

        mockMvc.perform(get("/api/v1/experiences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].type").value("SIMULATION"))
                .andExpect(jsonPath("$.items[0].summary").value(nullValue()))
                .andExpect(jsonPath("$.items[0].coverImage").value(nullValue()))
                .andExpect(jsonPath("$.items[0].loginRequired").value(false));
    }

    @Test
    void venuesAreListedForTheTrimmedCity() throws Exception {
        when(service.listVenues("370500", 1, 20)).thenReturn(new VenuePageView(
                1, 20, 1, false,
                List.of(new VenueCardView(
                        VENUE_ID, "东营低空与机器人体验馆", null, "09:30–18:00 · 周一闭馆", "东营区示例路 66 号", 3))));

        mockMvc.perform(get("/api/v1/venues").param("cityCode", " 370500 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].venueId").value(VENUE_ID.toString()))
                .andExpect(jsonPath("$.items[0].openSessionsToday").value(3));
    }

    @Test
    void venueDetailReturnsProjectsAndInformationRows() throws Exception {
        when(service.findVenue(VENUE_ID, "370500")).thenReturn(new VenueDetailView(
                VENUE_ID, "东营低空与机器人体验馆", null, "09:30–18:00 · 周一闭馆", "东营区示例路 66 号",
                37.4487, 118.5823, "0546-8000 0000",
                new VenueOperatorView(UUID.randomUUID(), "东营低空与机器人体验馆"),
                List.of(new VenueProjectView(PROJECT_ID, "机器人避障闯关", 30, 6, 8, null, 3900, "CNY", true)),
                List.of(new VenueInfoItemView("到馆须知", "请提前 10 分钟到馆")),
                6, new VenueCheckInRuleView(15, 10)));

        mockMvc.perform(get("/api/v1/venues/{venueId}", VENUE_ID).param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("0546-8000 0000"))
                .andExpect(jsonPath("$.checkIn.opensMinutesBeforeStart").value(15))
                .andExpect(jsonPath("$.checkIn.lateEntryMinutes").value(10))
                .andExpect(jsonPath("$.projects[0].priceAmountMinor").value(3900))
                // 1.1.0：能不能在线预约付款。
                .andExpect(jsonPath("$.projects[0].onlinePayment").value(true))
                .andExpect(jsonPath("$.projects[0].limitNote").value(nullValue()))
                .andExpect(jsonPath("$.infoItems[0].label").value("到馆须知"))
                .andExpect(jsonPath("$.deviceCount").value(6));
    }

    @Test
    void sessionsReturnTheDateAndIsoTimes() throws Exception {
        OffsetDateTime startsAt = OffsetDateTime.parse("2026-11-17T02:00:00Z");
        when(service.listSessions(VENUE_ID, "370500", PROJECT_ID, "2026-11-17")).thenReturn(
                new VenueSessionListView(LocalDate.parse("2026-11-17"), List.of(new VenueSessionView(
                        UUID.randomUUID(), startsAt, startsAt.plusMinutes(30), 8, 5, VenueSessionStatus.OPEN))));

        mockMvc.perform(get("/api/v1/venues/{venueId}/sessions", VENUE_ID)
                        .param("cityCode", "370500")
                        .param("projectId", PROJECT_ID.toString())
                        .param("date", "2026-11-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-11-17"))
                .andExpect(jsonPath("$.sessions[0].startsAt").value("2026-11-17T02:00:00Z"))
                .andExpect(jsonPath("$.sessions[0].endsAt").value("2026-11-17T02:30:00Z"))
                .andExpect(jsonPath("$.sessions[0].status").value("OPEN"));
    }

    @Test
    void devicesPassTheCategoryThrough() throws Exception {
        when(service.listDevices(VENUE_ID, "370500", "ROBOT", 1, 20)).thenReturn(new VenueDevicePageView(
                1, 20, 1, false,
                List.of(new VenueDeviceView(
                        UUID.randomUUID(), VenueDeviceCategory.ROBOT, "巡检机器狗", null, null,
                        VenueDeviceTrialMode.DISPLAY_ONLY))));

        mockMvc.perform(get("/api/v1/venues/{venueId}/devices", VENUE_ID)
                        .param("cityCode", "370500")
                        .param("category", "ROBOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].trialMode").value("DISPLAY_ONLY"))
                .andExpect(jsonPath("$.items[0].image").value(nullValue()));

        verify(service).listDevices(VENUE_ID, "370500", "ROBOT", 1, 20);
    }

    // R6-2：商品能在哪体验。城市去掉空白后交给读服务；没有关联时两个空列表，不是 404。
    @Test
    void productExperiencesReturnTheItemsAndTheVenuesOfTheCity() throws Exception {
        UUID product = UUID.fromString("c2000000-0000-4000-8000-000000000101");
        UUID item = UUID.fromString("b1000000-0000-4000-8000-000000000001");
        when(service.findProductExperiences(product, "370500")).thenReturn(new ProductExperiencesView(
                product,
                List.of(new ProductExperienceItemView(item, ExperienceType.SIMULATION, "机器人避障模拟", null)),
                List.of(new ProductExperienceVenueView(VENUE_ID, "东营低空与机器人体验馆", "编程机器人探索套装"))));
        UUID other = UUID.fromString("c2000000-0000-4000-8000-000000000301");
        when(service.findProductExperiences(other, "370500"))
                .thenReturn(new ProductExperiencesView(other, List.of(), List.of()));

        mockMvc.perform(get("/api/v1/experience/product-experiences")
                        .param("productId", product.toString())
                        .param("cityCode", " 370500 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(product.toString()))
                .andExpect(jsonPath("$.experiences[0].experienceId").value(item.toString()))
                .andExpect(jsonPath("$.experiences[0].type").value("SIMULATION"))
                .andExpect(jsonPath("$.experiences[0].summary").value(nullValue()))
                .andExpect(jsonPath("$.venues[0].venueId").value(VENUE_ID.toString()))
                .andExpect(jsonPath("$.venues[0].name").value("东营低空与机器人体验馆"))
                .andExpect(jsonPath("$.venues[0].deviceName").value("编程机器人探索套装"));
        mockMvc.perform(get("/api/v1/experience/product-experiences")
                        .param("productId", other.toString())
                        .param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experiences").isEmpty())
                .andExpect(jsonPath("$.venues").isEmpty());
    }

    @Test
    void productExperiencesRejectABadProductOrCity() throws Exception {
        when(service.findProductExperiences(null, "370500"))
                .thenThrow(new IllegalArgumentException("product id is required"));

        mockMvc.perform(get("/api/v1/experience/product-experiences").param("cityCode", "370500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/experience/product-experiences")
                        .param("productId", "not-a-uuid")
                        .param("cityCode", "370500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void badArgumentsAreBadRequestsAndUnknownVenuesAreNotFound() throws Exception {
        when(service.listVenues(null, 1, 20)).thenThrow(new IllegalArgumentException("city code is invalid"));
        when(service.findVenue(VENUE_ID, "370500")).thenThrow(new VenueNotFoundException());

        mockMvc.perform(get("/api/v1/venues"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/venues/not-a-uuid").param("cityCode", "370500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/venues/{venueId}/sessions", VENUE_ID)
                        .param("cityCode", "370500")
                        .param("projectId", "not-a-uuid")
                        .param("date", "2026-11-17"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/venues/{venueId}", VENUE_ID).param("cityCode", "370500"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }
}
