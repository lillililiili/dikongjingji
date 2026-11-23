package com.lowaltitude.inspection.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.search.application.SearchCorrectionView;
import com.lowaltitude.inspection.search.application.SearchGroupView;
import com.lowaltitude.inspection.search.application.SearchKeywordCorrections;
import com.lowaltitude.inspection.search.application.SearchPorts;
import com.lowaltitude.inspection.search.application.SearchReadService;
import com.lowaltitude.inspection.search.application.SearchResultView;
import com.lowaltitude.inspection.search.application.SearchScope;
import com.lowaltitude.inspection.search.application.SearchServiceHitView;
import com.lowaltitude.inspection.search.application.SearchServiceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.yaml.snakeyaml.Yaml;

class SearchControllerTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-search.yaml");

    private final List<String> reads = new ArrayList<>();
    private MockMvc mockMvc;

    private SearchPorts.Found one(String group, String city, String keyword, Map<String, Object> card) {
        reads.add(group + ":" + city + ":" + keyword);
        return new SearchPorts.Found(1, List.of(card));
    }

    @BeforeEach
    void setUp() {
        SearchReadService service = new SearchReadService(
                (city, keyword, limit) -> one("products", city, keyword, Map.of("name", "行业测绘无人机")),
                (city, keyword, limit) -> one("inspection", city, keyword, Map.of("title", "园区安防巡检")),
                (city, keyword, limit) -> one("pro", city, keyword, Map.of("title", "无人机航测与三维建模")),
                (city, keyword, limit) -> {
                    throw new IllegalStateException("activity store is down");
                },
                (city, keyword, limit) -> one("contents", city, keyword, Map.of("title", "无人机航测如何三天出图")),
                (city, keyword, limit) -> SearchPorts.Found.empty(),
                SearchKeywordCorrections.defaults());
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SearchController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void allScopeReturnsEveryGroupInTheShapeOfItsOwnList() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("cityCode", "370500").param("keyword", " 测绘 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.cityCode").value("370500"))
                .andExpect(jsonPath("$.keyword").value("测绘"))
                .andExpect(jsonPath("$.scope").value("ALL"))
                .andExpect(jsonPath("$.correction").value(nullValue()))
                .andExpect(jsonPath("$.products.total").value(1))
                .andExpect(jsonPath("$.products.items[0].name").value("行业测绘无人机"))
                .andExpect(jsonPath("$.services.total").value(2))
                .andExpect(jsonPath("$.services.items[0].kind").value("INSPECTION"))
                .andExpect(jsonPath("$.services.items[0].inspection.title").value("园区安防巡检"))
                .andExpect(jsonPath("$.services.items[0].proService").value(nullValue()))
                .andExpect(jsonPath("$.services.items[1].kind").value("PRO_SERVICE"))
                .andExpect(jsonPath("$.services.items[1].inspection").value(nullValue()))
                .andExpect(jsonPath("$.services.items[1].proService.title").value("无人机航测与三维建模"))
                // 活动模块读取失败：只这一组为空。
                .andExpect(jsonPath("$.activities.total").value(0))
                .andExpect(jsonPath("$.activities.items", hasSize(0)))
                .andExpect(jsonPath("$.contents.items[0].title").value("无人机航测如何三天出图"))
                .andExpect(jsonPath("$.venues.items", hasSize(0)));
    }

    @Test
    void aScopeLeavesTheOtherGroupsNullAndATypoGetsACorrection() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                        .param("cityCode", "370500").param("keyword", "巡简").param("scope", "SERVICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("SERVICE"))
                .andExpect(jsonPath("$.correction.keyword").value("巡检"))
                .andExpect(jsonPath("$.products").value(nullValue()))
                .andExpect(jsonPath("$.activities").value(nullValue()))
                .andExpect(jsonPath("$.contents").value(nullValue()))
                .andExpect(jsonPath("$.venues").value(nullValue()))
                .andExpect(jsonPath("$.services.items", hasSize(2)));
        assertThat(reads).containsExactly("inspection:370500:巡简", "pro:370500:巡简");
    }

    @Test
    void badInputIsABadRequestAndNothingIsRead() throws Exception {
        String[][] cases = {
                {null, "测绘", null, null},
                {"x", "测绘", null, null},
                {"370500", null, null, null},
                {"370500", "  ", null, null},
                {"370500", "测".repeat(41), null, null},
                {"370500", "测绘", "PEOPLE", null},
                {"370500", "测绘", "ALL", "6"},
                {"370500", "测绘", "VENUE", "21"},
        };
        for (String[] item : cases) {
            var request = get("/api/v1/search");
            if (item[0] != null) request = request.param("cityCode", item[0]);
            if (item[1] != null) request = request.param("keyword", item[1]);
            if (item[2] != null) request = request.param("scope", item[2]);
            if (item[3] != null) request = request.param("size", item[3]);
            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }
        assertThat(reads).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void contractMatchesTheViewsAndIsPublic() throws Exception {
        Map<String, Object> root = new Yaml().load(Files.readString(CONTRACT));
        assertThat(((Map<String, Object>) root.get("info")).get("version")).isEqualTo("1.0.0");
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");
        assertThat(paths).containsOnlyKeys("/api/v1/search");
        Map<String, Object> operation = (Map<String, Object>) ((Map<String, Object>) paths.get("/api/v1/search")).get("get");
        assertThat(operation.get("security")).isEqualTo(List.of());
        assertThat(((List<Map<String, Object>>) operation.get("parameters")))
                .extracting(parameter -> parameter.get("name"))
                .containsExactly("cityCode", "keyword", "scope", "size");

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) root.get("components")).get("schemas");
        assertThat(properties(schemas, "SearchResult")).containsExactlyElementsOf(components(SearchResultView.class));
        assertThat(properties(schemas, "SearchCorrection")).containsExactlyElementsOf(components(SearchCorrectionView.class));
        assertThat(properties(schemas, "SearchServiceHit")).containsExactlyElementsOf(components(SearchServiceHitView.class));
        for (String group : List.of("SearchProductGroup", "SearchServiceGroup", "SearchActivityGroup",
                "SearchContentGroup", "SearchVenueGroup")) {
            assertThat(properties(schemas, group)).as(group).containsExactlyElementsOf(components(SearchGroupView.class));
        }
        assertThat((List<String>) ((Map<String, Object>) schemas.get("SearchScope")).get("enum"))
                .containsExactlyElementsOf(Arrays.stream(SearchScope.values()).map(Enum::name).toList());
        Map<String, Object> kind = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>)
                schemas.get("SearchServiceHit")).get("properties")).get("kind");
        assertThat((List<String>) kind.get("enum"))
                .containsExactlyElementsOf(Arrays.stream(SearchServiceKind.values()).map(Enum::name).toList());
        assertThat(Files.readString(CONTRACT))
                .contains("openapi-mall-catalog.yaml#/components/schemas/MallProductCard")
                .contains("openapi-inspection-catalog.yaml#/components/schemas/InspectionServiceCard")
                .contains("openapi-proservice.yaml#/components/schemas/ProServiceCard")
                .contains("openapi-activity.yaml#/components/schemas/ActivityCard")
                .contains("openapi-content.yaml#/components/schemas/ContentArticleCard")
                .contains("openapi-experience.yaml#/components/schemas/VenueCard");
    }

    @SuppressWarnings("unchecked")
    private static List<String> properties(Map<String, Object> schemas, String name) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
        assertThat((List<String>) schema.get("required"))
                .as(name + " requires every property")
                .containsExactlyInAnyOrderElementsOf(((Map<String, Object>) schema.get("properties")).keySet());
        return new ArrayList<>(((Map<String, Object>) schema.get("properties")).keySet());
    }

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
