package com.lowaltitude.inspection.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityHomeFeed;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.experience.application.ExperienceHomeFeed;
import com.lowaltitude.inspection.mall.application.MallHomeFeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HomeRecommendControllerTest {

    private static final UUID PROJECT_ID = UUID.fromString("b4000000-0000-4000-8000-000000000101");

    private final ActivityHomeFeed activities = mock(ActivityHomeFeed.class);
    private final MallHomeFeed products = mock(MallHomeFeed.class);
    private final ExperienceHomeFeed venues = mock(ExperienceHomeFeed.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new HomeRecommendController(activities, products, venues))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void joinsTheFourBlocksWithFixedSizes() throws Exception {
        Map<String, String> venue = Map.of("name", "东营低空与机器人体验馆");
        when(activities.upcoming("370500", 3)).thenReturn((List) List.of(Map.of("title", "机器人研学体验")));
        when(products.featured("370500", 4)).thenReturn((List) List.of(Map.of("name", "编程机器人探索套装")));
        when(venues.load("370500", 2, 3)).thenReturn(new ExperienceHomeFeed.Feed(
                List.of(venue),
                new ExperienceHomeFeed.Today(venue, List.of(new ExperienceHomeFeed.TodaySlot(
                        PROJECT_ID, "机器人避障闯关", OffsetDateTime.parse("2026-11-17T14:00:00+08:00"), 6)))));

        mockMvc.perform(get("/api/v1/home/recommend").param("cityCode", " 370500 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.today.venue.name").value("东营低空与机器人体验馆"))
                .andExpect(jsonPath("$.today.slots[0].projectId").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.today.slots[0].projectName").value("机器人避障闯关"))
                .andExpect(jsonPath("$.today.slots[0].seatsLeft").value(6))
                .andExpect(jsonPath("$.activities[0].title").value("机器人研学体验"))
                .andExpect(jsonPath("$.products[0].name").value("编程机器人探索套装"))
                .andExpect(jsonPath("$.venues", hasSize(1)));
    }

    // 首页不能因为一个模块读不出来就整块空白。
    @Test
    void aFailingModuleLeavesOnlyItsOwnBlockEmpty() throws Exception {
        when(activities.upcoming("370500", 3)).thenThrow(new IllegalStateException("activity store is down"));
        when(products.featured("370500", 4)).thenReturn((List) List.of(Map.of("name", "编程机器人探索套装")));
        when(venues.load("370500", 2, 3)).thenThrow(new IllegalStateException("venue store is down"));

        mockMvc.perform(get("/api/v1/home/recommend").param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today").value(nullValue()))
                .andExpect(jsonPath("$.activities", hasSize(0)))
                .andExpect(jsonPath("$.venues", hasSize(0)))
                .andExpect(jsonPath("$.products[0].name").value("编程机器人探索套装"));
    }

    @Test
    void aMissingOrMalformedCityIsABadRequestAndNothingIsRead() throws Exception {
        for (String city : new String[] {null, "", "x", "370500; DROP"}) {
            var request = get("/api/v1/home/recommend");
            if (city != null) {
                request = request.param("cityCode", city);
            }
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }
        org.mockito.Mockito.verifyNoInteractions(activities, products, venues);
    }

    @Test
    void contractDeclaresTheRouteAndTheFourBlocks() throws Exception {
        String contract = Files.readString(Path.of("..", "..", "contracts", "openapi-home.yaml"));

        assertThat(contract)
                .contains("/api/v1/home/recommend:")
                .contains("security: []")
                .contains("required: [today, activities, products, venues]")
                .contains("required: [projectId, projectName, startsAt, seatsLeft]")
                .contains("openapi-activity.yaml#/components/schemas/ActivityCard")
                .contains("openapi-mall-catalog.yaml#/components/schemas/MallProductCard")
                .contains("openapi-experience.yaml#/components/schemas/VenueCard");
        assertThat(HomeRecommendController.ACTIVITY_COUNT).isEqualTo(3);
        assertThat(HomeRecommendController.PRODUCT_COUNT).isEqualTo(4);
        assertThat(HomeRecommendController.VENUE_COUNT).isEqualTo(2);
        assertThat(HomeRecommendController.TODAY_PROJECT_COUNT).isEqualTo(3);
        verify(activities, org.mockito.Mockito.never()).upcoming(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }
}
