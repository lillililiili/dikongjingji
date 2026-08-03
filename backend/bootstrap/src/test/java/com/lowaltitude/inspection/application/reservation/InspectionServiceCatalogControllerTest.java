package com.lowaltitude.inspection.application.reservation;

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
                .andExpect(jsonPath("$.reviews.total").value(0));
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
                                "RESERVABLE",
                                "RESERVE",
                                8_560L))));

        mockMvc.perform(get("/api/v1/inspection/services")
                        .param("cityCode", "370500")
                        .param("categoryCode", "POWER")
                        .param("sort", "SALES_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].completedCount").value(8560));

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
                "最终报价以现场评估为准",
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
}
