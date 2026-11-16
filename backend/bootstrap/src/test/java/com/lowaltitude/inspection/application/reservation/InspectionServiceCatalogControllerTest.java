package com.lowaltitude.inspection.application.reservation;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InspectionServiceCatalogControllerTest {

    private final InspectionServiceCatalogReadService service =
            mock(InspectionServiceCatalogReadService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new InspectionServiceCatalogController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsCompleteServiceDetailByServiceAndCity() throws Exception {
        UUID serviceId = UUID.randomUUID();
        when(service.findDetail(serviceId, "370500"))
                .thenReturn(detail(serviceId));

        mockMvc.perform(get("/api/v1/inspection/services/{serviceId}", serviceId)
                        .param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceId").value(serviceId.toString()))
                .andExpect(jsonPath("$.provider.name").value("云翼科技"))
                .andExpect(jsonPath("$.priceNote").value("按线路公里计价，起步价含现场巡检和报告"))
                .andExpect(jsonPath("$.priceFactors[0]").value("线路长度"))
                .andExpect(jsonPath("$.priceFactors[2]").value("现场条件"))
                .andExpect(jsonPath("$.cancellationNote")
                        .value("方案确认前可取消，不收费；确认后按方案约定"))
                .andExpect(jsonPath("$.serviceCities[0].code").value("370500"))
                .andExpect(jsonPath("$.serviceCities[0].name").value("东营"))
                .andExpect(jsonPath("$.serviceCities[0].provinceCode").value("370000"))
                .andExpect(jsonPath("$.reviews.total").value(0));
    }

    @Test
    void serviceDetailWithoutPricingConfigurationReturnsNullFields() throws Exception {
        UUID serviceId = UUID.randomUUID();
        when(service.findDetail(serviceId, "370500"))
                .thenReturn(detailWithoutPricingConfiguration(serviceId));

        mockMvc.perform(get("/api/v1/inspection/services/{serviceId}", serviceId)
                        .param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceNote").value(nullValue()))
                .andExpect(jsonPath("$.priceFactors").value(nullValue()))
                .andExpect(jsonPath("$.cancellationNote").value(nullValue()))
                .andExpect(jsonPath("$.serviceCities").value(nullValue()));
    }

    @Test
    void missingServiceDetailUsesCommonNotFoundError() throws Exception {
        UUID serviceId = UUID.randomUUID();
        when(service.findDetail(serviceId, "370500"))
                .thenThrow(new InspectionServiceNotFoundException());

        mockMvc.perform(get("/api/v1/inspection/services/{serviceId}", serviceId)
                        .param("cityCode", "370500"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    void forwardsPowerSalesSortAndReturnsCompletedCount() throws Exception {
        UUID serviceId = UUID.randomUUID();
        when(service.search("370500", "POWER", "SALES_DESC", 1, 20))
                .thenReturn(new InspectionServicePageView(
                        1,
                        20,
                        1,
                        false,
                        List.of(new InspectionServiceCardView(
                                serviceId,
                                "INS-SVC-001",
                                new CodeNameView("POWER", "输电线路"),
                                "输电线路无人机巡检",
                                "杆塔与线路巡检",
                                null,
                                List.of(),
                                new ServicePriceView(
                                        "STARTING_PRICE",
                                        69_900L,
                                        "CNY",
                                        "KM",
                                        "公里"),
                                "含现场巡检和报告",
                                "RESERVABLE",
                                "RESERVE",
                                8_560L))));

        mockMvc.perform(get("/api/v1/inspection/services")
                        .param("cityCode", "370500")
                        .param("categoryCode", "POWER")
                        .param("sort", "SALES_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].completedCount").value(8560))
                .andExpect(jsonPath("$.items[0].priceNote").value("含现场巡检和报告"));

        verify(service).search("370500", "POWER", "SALES_DESC", 1, 20);
    }

    private static InspectionServiceDetailView detail(UUID serviceId) {
        return new InspectionServiceDetailView(
                serviceId,
                "INS-SVC-001",
                new CodeNameView("POWER", "输电巡检"),
                "输电线路无人机巡检",
                "杆塔精细巡检",
                null,
                new ServicePriceView(
                        "STARTING_PRICE",
                        69_900L,
                        "CNY",
                        "KM",
                        "公里"),
                "按线路公里计价，起步价含现场巡检和报告",
                List.of("线路长度", "杆塔数量", "现场条件"),
                "方案确认前可取消，不收费；确认后按方案约定",
                List.of(new CitySummaryView("370500", "东营", "370000")),
                "RESERVABLE",
                "RESERVE",
                8_560L,
                new ServiceProviderView(
                        UUID.randomUUID(),
                        "云翼科技",
                        null,
                        "FEIXIANG_APP_ICON",
                        "企业认证",
                        "专业飞手团队"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new ReviewSummaryView(null, 0L, null, List.of(), List.of()));
    }

    private static InspectionServiceDetailView detailWithoutPricingConfiguration(
            UUID serviceId
    ) {
        return new InspectionServiceDetailView(
                serviceId,
                "INS-SVC-002",
                new CodeNameView("PARK", "园区"),
                "园区综合巡检服务",
                "安防巡查",
                null,
                new ServicePriceView(
                        "PROJECT_QUOTE",
                        null,
                        "CNY",
                        null,
                        null),
                null,
                null,
                null,
                null,
                "AVAILABLE",
                "REQUEST_QUOTE",
                0L,
                new ServiceProviderView(
                        UUID.randomUUID(),
                        "云翼科技",
                        null,
                        "FEIXIANG_APP_ICON",
                        "企业认证",
                        "专业飞手团队"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new ReviewSummaryView(null, 0L, null, List.of(), List.of()));
    }
}
