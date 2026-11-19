package com.lowaltitude.inspection.proservice.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * 专业服务的公开读取接口（F2）：把查询参数原样交给读服务，把结果和 400/404 转成契约里的形状。
 * 不登录也能调，在 AuthenticationSecurityIntegrationTest 里检查。
 */
class ProServiceControllerTest {

    private static final UUID SERVICE = UUID.fromString("f2100000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INSPECTION = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final ProServiceReadService service = mock(ProServiceReadService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProServiceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @Test
    void theCatalogPassesEveryParameterAndAnswersThePage() throws Exception {
        when(service.list("AERIAL", "370500", "园区", List.of("PHOTOS", "VIDEO_FOOTAGE"), "PRICE_FROM", "PRICE_ASC",
                2, 10)).thenReturn(page());

        mockMvc.perform(get("/api/v1/pro-services?type=AERIAL&cityCode=370500&keyword=园区"
                        + "&deliverables=PHOTOS&deliverables=VIDEO_FOOTAGE&priceMode=PRICE_FROM&sort=PRICE_ASC"
                        + "&page=2&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$.type").value("AERIAL"))
                .andExpect(jsonPath("$.cityName").value("东营市"))
                .andExpect(jsonPath("$.cityOpen").value(true))
                .andExpect(jsonPath("$.facetGroup.label").value("成果类型"))
                .andExpect(jsonPath("$.facetGroup.options[1].code").value("PHOTOS"))
                .andExpect(jsonPath("$.facetGroup.options[1].label").value("照片"))
                .andExpect(jsonPath("$.sort").value("PRICE_ASC"))
                .andExpect(jsonPath("$.items[0].length()").value(9))
                .andExpect(jsonPath("$.items[0].category.code").value("ENGINEERING"))
                .andExpect(jsonPath("$.items[0].category.label").value("工程"))
                .andExpect(jsonPath("$.items[0].price.mode").value("PRICE_FROM"))
                .andExpect(jsonPath("$.items[0].price.amountMinor").value(128000))
                .andExpect(jsonPath("$.items[0].price.unit").value("/次起"))
                .andExpect(jsonPath("$.items[0].tags[2]").value("持证飞手"))
                .andExpect(jsonPath("$.items[0].sample").value(true))
                .andExpect(jsonPath("$.items[1].price.mode").value("PROJECT"))
                .andExpect(jsonPath("$.items[1].price.note").value("方案确认后定价"));
        // 逗号分隔也可以；不传的参数是 null，页码默认 1、20。
        mockMvc.perform(get("/api/v1/pro-services?type=SURVEY&cityCode=370500&deliverables=ORTHOPHOTO,MODEL_3D"));
        verify(service).list("SURVEY", "370500", null, List.of("ORTHOPHOTO", "MODEL_3D"), null, null, 1, 20);
    }

    @Test
    void invalidCatalogParametersAreABadRequest() throws Exception {
        when(service.list(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new IllegalArgumentException("type is required"));

        mockMvc.perform(get("/api/v1/pro-services?cityCode=370500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/pro-services?type=AERIAL&cityCode=370500&page=x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void theDetailAnswersEverySection() throws Exception {
        when(service.detail(SERVICE, "370500")).thenReturn(detail());

        mockMvc.perform(get("/api/v1/pro-services/" + SERVICE + "?cityCode=370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(18))
                .andExpect(jsonPath("$.coverUrl").doesNotExist())
                .andExpect(jsonPath("$.capabilities[0]").value("视频素材"))
                .andExpect(jsonPath("$.priceRows[0].label").value("计价方式"))
                .andExpect(jsonPath("$.priceRows[0].detail").value("按次计价，真实金额以方案报价为准"))
                .andExpect(jsonPath("$.outputs[0].label").value("视频素材"))
                .andExpect(jsonPath("$.serviceModes.length()").value(0))
                .andExpect(jsonPath("$.terms.length()").value(0))
                .andExpect(jsonPath("$.steps[3]").value("成果验收"))
                .andExpect(jsonPath("$.provider.providerId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.provider.certificationLabel").value("企业认证"))
                .andExpect(jsonPath("$.cities[0].cityName").value("东营市"));
    }

    @Test
    void unknownServicesAndProvidersAreNotFoundAndBadIdsAreBadRequests() throws Exception {
        when(service.detail(eq(SERVICE), any())).thenThrow(new ProServiceNotFoundException());
        when(service.provider(eq(PROVIDER), any())).thenThrow(new ProServiceNotFoundException());

        mockMvc.perform(get("/api/v1/pro-services/" + SERVICE + "?cityCode=370100"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/providers/" + PROVIDER + "?cityCode=370500"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/providers/not-a-uuid?cityCode=370500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void theProviderPageAnswersItsServicesAndCities() throws Exception {
        UUID other = UUID.fromString("f2000000-0000-4000-8000-000000000001");
        when(service.provider(other, "370500")).thenReturn(new ProviderPageView(other, "鹰眼智能巡检", "资质已审核",
                "东营本地团队", "370500",
                List.of(new ProviderInspectionServiceView(INSPECTION, "电力输电线路无人机巡检", "高精度巡检", "电力",
                        new ProServicePriceView(ProServicePriceMode.PRICE_FROM, 69900L, "/公里起", "含现场巡检和报告"))),
                page().items(),
                List.of(new ProServiceCityView("370500", "东营市"))));

        mockMvc.perform(get("/api/v1/providers/" + other + "?cityCode=370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$.name").value("鹰眼智能巡检"))
                .andExpect(jsonPath("$.inspectionServices[0].serviceId").value(INSPECTION.toString()))
                .andExpect(jsonPath("$.inspectionServices[0].price.unit").value("/公里起"))
                .andExpect(jsonPath("$.proServices.length()").value(2))
                .andExpect(jsonPath("$.cities[0].cityCode").value("370500"));
    }

    private static ProServicePageView page() {
        return new ProServicePageView(ProServiceType.AERIAL, "370500", "东营市", true,
                new ProServiceFacetGroupView("成果类型", List.of(
                        new ProServiceFacetView(ProServiceFacet.VIDEO_FOOTAGE, "视频素材"),
                        new ProServiceFacetView(ProServiceFacet.PHOTOS, "照片"))),
                ProServiceSort.PRICE_ASC, 2, 10, 12, false, List.of(
                        new ProServiceCardView(SERVICE, ProServiceType.AERIAL,
                                new ProServiceCategoryView(ProServiceCategory.ENGINEERING, "工程"), "园区与工程航拍",
                                "园区全景 · 工程进度 · 宣传素材", List.of("视频素材", "照片", "持证飞手"),
                                new ProServicePriceView(ProServicePriceMode.PRICE_FROM, 128000L, "/次起", "含拍摄和素材整理"),
                                null, true),
                        new ProServiceCardView(UUID.randomUUID(), ProServiceType.AERIAL,
                                new ProServiceCategoryView(ProServiceCategory.ENERGY, "能源"), "能源设施航拍",
                                "光伏场站 · 厂区全景 · 资料存档", List.of("视频素材"),
                                new ProServicePriceView(ProServicePriceMode.PROJECT, null, null, "方案确认后定价"),
                                null, true)));
    }

    private static ProServiceDetailView detail() {
        return new ProServiceDetailView(SERVICE, ProServiceType.AERIAL,
                new ProServiceCategoryView(ProServiceCategory.ENGINEERING, "工程"), "园区与工程航拍",
                "园区全景 · 工程进度 · 宣传素材",
                new ProServicePriceView(ProServicePriceMode.PRICE_FROM, 128000L, "/次起", "含拍摄和素材整理"), null, true,
                List.of("视频素材", "照片", "持证飞手"),
                List.of(new ProServiceLineView("计价方式", "按次计价，真实金额以方案报价为准")),
                List.of("园区全景", "工程进度"),
                List.of(new ProServiceLineView("视频素材", "原始素材，分辨率和时长按方案约定")),
                List.of(), List.of(),
                List.of("提交需求", "报价比选", "现场拍摄", "成果验收"),
                List.of("持证飞手", "合规飞行", "素材保密"),
                new ProServiceProviderView(PROVIDER, "云翼科技", "企业认证", "专业飞手团队"),
                List.of(new ProServiceCityView("370500", "东营市")));
    }
}
