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
                .andExpect(jsonPath("$.demonstrationMetrics").value(true))
                .andExpect(jsonPath("$.provider.qualificationReviewed").value(true))
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
                .andExpect(jsonPath("$.demonstrationMetrics").value(false))
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

    // 不带城市时由读取服务按参数无效处理，和列表、入口、筛选三个接口一致，不是 500。
    @Test
    void serviceDetailWithoutACityIsABadRequest() throws Exception {
        UUID serviceId = UUID.randomUUID();
        when(service.findDetail(serviceId, null))
                .thenThrow(new IllegalArgumentException("service detail query is invalid"));

        mockMvc.perform(get("/api/v1/inspection/services/{serviceId}", serviceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void forwardsPowerSalesSortAndReturnsCompletedCount() throws Exception {
        UUID serviceId = UUID.randomUUID();
        InspectionCatalogSearch search = new InspectionCatalogSearch(
                "370500", null, "POWER", null, null, null, null,
                "SALES_DESC", null, null, 1, 20);
        when(service.search(search))
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
                                8_560L,
                                null,
                                "示例电力巡检服务商"))));

        mockMvc.perform(get("/api/v1/inspection/services")
                        .param("cityCode", "370500")
                        .param("categoryCode", "POWER")
                        .param("sort", "SALES_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].completedCount").value(8560))
                .andExpect(jsonPath("$.items[0].priceNote").value("含现场巡检和报告"))
                .andExpect(jsonPath("$.items[0].providerName").value("示例电力巡检服务商"));

        verify(service).search(search);
    }

    @Test
    void forwardsKeywordFiltersAndPositionAsTheCustomerSentThem() throws Exception {
        InspectionCatalogSearch search = new InspectionCatalogSearch(
                "370500",
                "线路",
                "POWER",
                List.of("UAV", "ROBOT"),
                null,
                List.of("PROJECT_QUOTE"),
                true,
                "DISTANCE_ASC",
                37.43,
                118.67,
                2,
                5);
        when(service.search(search))
                .thenReturn(new InspectionServicePageView(2, 5, 6, false, List.of()));

        mockMvc.perform(get("/api/v1/inspection/services")
                        .param("cityCode", " 370500 ")
                        .param("keyword", "线路")
                        .param("categoryCode", "POWER")
                        .param("serviceMethods", "UAV")
                        .param("serviceMethods", "ROBOT")
                        .param("priceModes", "PROJECT_QUOTE")
                        .param("includesReport", "true")
                        .param("sort", "DISTANCE_ASC")
                        .param("latitude", "37.43")
                        .param("longitude", "118.67")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(service).search(search);
    }

    @Test
    void invalidListQueriesAreBadRequestsAndClosedCitiesHaveTheirOwnCode() throws Exception {
        when(service.search(new InspectionCatalogSearch(
                null, null, null, null, null, null, null, "COMPREHENSIVE", null, null, 1, 20)))
                .thenThrow(new IllegalArgumentException("catalog query is invalid"));
        when(service.search(new InspectionCatalogSearch(
                "370700", null, null, null, null, null, null, "COMPREHENSIVE", null, null, 1, 20)))
                .thenThrow(new InspectionCatalogCityNotEnabledException());

        mockMvc.perform(get("/api/v1/inspection/services"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/inspection/services")
                        .param("cityCode", "370500")
                        .param("latitude", "north"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/inspection/services").param("cityCode", "370700"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INS_CATALOG_CITY_NOT_ENABLED"))
                .andExpect(jsonPath("$.message").value("当前城市暂未开通巡检服务"));
    }

    @Test
    void portalAndFiltersAreServedPerCity() throws Exception {
        CitySummaryView city = new CitySummaryView("370500", "东营市", "370000");
        when(service.findPortal("370500")).thenReturn(new InspectionPortalView(
                city,
                "1794700800",
                java.time.OffsetDateTime.parse("2026-11-15T08:00:00+08:00"),
                new HeroContentView(
                        "专业巡检", "隐患早发现", "", List.of(), null, null,
                        PortalActionView.openCatalog(null)),
                List.of(new InspectionCategoryView(
                        "POWER", "电力", null, null, 10, PortalActionView.openCatalog("POWER"))),
                List.of(),
                List.of(),
                List.of(new PortalInfoItemView("BOOK", "提交预约", "选服务和时间", null, 10))));
        when(service.findFilterOptions("370500")).thenReturn(new InspectionFilterOptionsView(
                city,
                "1794700800",
                List.of(),
                List.of(new CodeNameView("UAV", "无人机")),
                List.of(),
                List.of(new CodeNameView("PROJECT_QUOTE", "按项目报价")),
                List.of("COMPREHENSIVE", "DISTANCE_ASC"),
                true));

        mockMvc.perform(get("/api/v1/inspection/portal").param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city.name").value("东营市"))
                .andExpect(jsonPath("$.hero.highlightedText").value("隐患早发现"))
                .andExpect(jsonPath("$.categories[0].action.type").value("OPEN_CATALOG"))
                .andExpect(jsonPath("$.categories[0].action.categoryCode").value("POWER"))
                .andExpect(jsonPath("$.guarantees").isEmpty())
                .andExpect(jsonPath("$.processSteps[0].title").value("提交预约"));
        mockMvc.perform(get("/api/v1/inspection/filters").param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceMethods[0].code").value("UAV"))
                .andExpect(jsonPath("$.sorts[1]").value("DISTANCE_ASC"))
                .andExpect(jsonPath("$.supportsReportFilter").value(true));
    }

    @Test
    void portalErrorsUseTheContractCodes() throws Exception {
        when(service.findPortal(null))
                .thenThrow(new IllegalArgumentException("city code is invalid"));
        when(service.findPortal("370700"))
                .thenThrow(new InspectionCatalogCityNotEnabledException());
        when(service.findPortal("370500"))
                .thenThrow(new InspectionPortalConfigUnavailableException());
        when(service.findFilterOptions("370700"))
                .thenThrow(new InspectionCatalogCityNotEnabledException());

        mockMvc.perform(get("/api/v1/inspection/portal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/inspection/portal").param("cityCode", "370700"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INS_CATALOG_CITY_NOT_ENABLED"));
        mockMvc.perform(get("/api/v1/inspection/portal").param("cityCode", "370500"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INS_CATALOG_CONFIG_UNAVAILABLE"));
        mockMvc.perform(get("/api/v1/inspection/filters").param("cityCode", "370700"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INS_CATALOG_CITY_NOT_ENABLED"));
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
                true,
                new ServiceProviderView(
                        UUID.randomUUID(),
                        "云翼科技",
                        null,
                        "FEIXIANG_APP_ICON",
                        "企业认证",
                        true,
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
                false,
                new ServiceProviderView(
                        UUID.randomUUID(),
                        "云翼科技",
                        null,
                        "FEIXIANG_APP_ICON",
                        "企业认证",
                        false,
                        "专业飞手团队"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new ReviewSummaryView(null, 0L, null, List.of(), List.of()));
    }
}
