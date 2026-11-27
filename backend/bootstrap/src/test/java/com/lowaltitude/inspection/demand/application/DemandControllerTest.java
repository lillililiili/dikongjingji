package com.lowaltitude.inspection.demand.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.demand.application.DemandException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 需求与报价的两个控制器（F1）：从令牌里取账号、角色和机构，把请求转成服务的参数，把结果、ETag 和业务拒绝转成契约里的
 * 状态码。没带令牌时的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里逐个接口检查。
 */
class DemandControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ORG = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID OPERATOR = UUID.fromString("22222222-2222-2222-2222-aaaaaaaaaaaa");
    private static final UUID PROVIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEMAND = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private static final UUID QUOTE = UUID.fromString("d2000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-19T02:00:00Z");

    /** 每一种业务拒绝对外的状态码和错误码（GlobalExceptionHandler 与契约 1.0.0）。 */
    private static final Map<Error, Rejection> REJECTIONS = Map.ofEntries(
            Map.entry(Error.NOT_FOUND, new Rejection(404, "COMMON_NOT_FOUND")),
            Map.entry(Error.FORBIDDEN, new Rejection(403, "COMMON_FORBIDDEN")),
            Map.entry(Error.IDEMPOTENCY_CONFLICT, new Rejection(409, "DEMAND_IDEMPOTENCY_KEY_REUSED")),
            Map.entry(Error.INCOMPLETE, new Rejection(422, "DEMAND_INCOMPLETE")),
            Map.entry(Error.BOOKING_CONFLICT, new Rejection(409, "DEMAND_BOOKING_CONFLICT")),
            Map.entry(Error.BOOKING_SERVICE_UNAVAILABLE, new Rejection(409, "DEMAND_BOOKING_SERVICE_UNAVAILABLE")),
            Map.entry(Error.STATE_INVALID, new Rejection(409, "DEMAND_STATE_INVALID")),
            Map.entry(Error.VERSION_CONFLICT, new Rejection(412, "DEMAND_VERSION_CONFLICT")),
            Map.entry(Error.QUOTING_CLOSED, new Rejection(409, "DEMAND_QUOTING_CLOSED")),
            Map.entry(Error.QUOTE_CHANGED, new Rejection(409, "DEMAND_QUOTE_CHANGED")),
            Map.entry(Error.QUOTE_EXPIRED, new Rejection(409, "DEMAND_QUOTE_EXPIRED")),
            Map.entry(Error.QUOTE_LOCKED, new Rejection(409, "DEMAND_QUOTE_LOCKED")));

    private final DemandService service = mock(DemandService.class);
    private final IdentityProfilePort profiles = mock(IdentityProfilePort.class);
    private final TestingAuthenticationToken customer = signedIn(SUBJECT, CUSTOMER_ORG, "CUSTOMER");
    private final TestingAuthenticationToken operator = signedIn(OPERATOR, PROVIDER, "PROVIDER_OPERATOR");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DemandController(service, profiles), new DemandProviderController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    // ---- 谁能调 ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("customerRoutes")
    void customerRoutesRefuseAnAccountThatIsNotACustomer(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request.principal(operator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(service, profiles);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providerRoutes")
    void providerRoutesNeedAProviderOperatorOfAnOrganization(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        for (TestingAuthenticationToken who : List.of(
                customer,
                signedIn(OPERATOR, null, "PROVIDER_OPERATOR"),
                signedIn(OPERATOR, PROVIDER, "PROVIDER_ADMIN"))) {
            mockMvc.perform(request.principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allRoutes")
    void noRouteWorksWithoutASignedInSubject(String route, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(service, profiles);
    }

    // 可以邀请的服务商只要登录：服务商操作员也能读（没有个人信息）。
    @Test
    void invitableProvidersNeedOnlyALogin() throws Exception {
        when(service.invitableProviders("370500", "INSPECTION")).thenReturn(new DemandProviderListView(List.of(
                new DemandProviderView(PROVIDER, "云翼科技", "企业认证", null, true))));

        mockMvc.perform(get("/api/v1/demand-providers?cityCode=370500&type=INSPECTION").principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].providerId").value(PROVIDER.toString()))
                .andExpect(jsonPath("$.items[0].name").value("云翼科技"))
                .andExpect(jsonPath("$.items[0].certificationLabel").value("企业认证"))
                .andExpect(jsonPath("$.items[0].qualificationReviewed").value(true))
                .andExpect(jsonPath("$.items[0].description").doesNotExist());
    }

    // ---- 客户 ----

    @Test
    void creatingRecordsTheDemandUnderTheCustomerOrganizationAndAnswers201WithAnETag() throws Exception {
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.of(
                new IdentityProfile(SUBJECT, "王女士（示例）", "东营示例物流公司", "CUSTOMER")));
        DemandDraft expected = new DemandDraft("INSPECTION", "370500", "东营经济技术开发区", "约 1.2 平方公里",
                "2026-11-30", 1_000_000L, 2_000_000L, List.of("INSPECTION_REPORT", "HAZARD_LIST"), "夜间不作业",
                "INVITE", List.of(PROVIDER));
        when(service.create(SUBJECT, new DemandPublisher(CUSTOMER_ORG, "东营示例物流公司"), KEY, expected, true))
                .thenReturn(new DemandCreated(demand(DemandStatus.OPEN, 1), true));

        mockMvc.perform(post("/api/v1/demands").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"INSPECTION","cityCode":"370500","address":"东营经济技术开发区",
                                 "scope":"约 1.2 平方公里","dueDate":"2026-11-30","budgetMinMinor":1000000,
                                 "budgetMaxMinor":2000000,"deliverables":["INSPECTION_REPORT","HAZARD_LIST"],
                                 "note":"夜间不作业","quoteScope":"INVITE","invitedProviderIds":["%s"],"submit":true}
                                """.formatted(PROVIDER)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.length()").value(30))
                .andExpect(jsonPath("$.demandId").value(DEMAND.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closeReason").doesNotExist())
                .andExpect(jsonPath("$.type").value("INSPECTION"))
                .andExpect(jsonPath("$.title").value("巡检需求"))
                .andExpect(jsonPath("$.publisher.kind").value("ORGANIZATION"))
                .andExpect(jsonPath("$.publisher.organizationName").value("东营示例物流公司"))
                .andExpect(jsonPath("$.dueDate").value("2026-11-30"))
                .andExpect(jsonPath("$.deliverables[0].code").value("INSPECTION_REPORT"))
                .andExpect(jsonPath("$.deliverables[0].label").value("巡检报告"))
                .andExpect(jsonPath("$.invitedProviders[0].name").value("云翼科技"))
                .andExpect(jsonPath("$.quoteDeadline").value("2026-11-22T02:00:00Z"))
                .andExpect(jsonPath("$.quotingOpen").value(true))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void anAccountWithoutACustomerOrganizationPublishesPersonallyAndAReplayAnswers200() throws Exception {
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.of(
                new IdentityProfile(SUBJECT, "王女士（示例）", "云翼科技", "PROVIDER")));
        when(service.create(eq(SUBJECT), eq(DemandPublisher.personal()), eq(KEY), any(), eq(false)))
                .thenReturn(new DemandCreated(demand(DemandStatus.DRAFT, 0), false));

        mockMvc.perform(post("/api/v1/demands").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"AERIAL\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""));
        // 令牌里没有机构：不查资料，直接是个人。
        mockMvc.perform(post("/api/v1/demands").principal(signedIn(SUBJECT, null, "CUSTOMER"))
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"AERIAL\"}"))
                .andExpect(status().isOk());
        verify(profiles).findProfile(SUBJECT);
    }

    @Test
    void creatingNeedsAUuidIdempotencyKey() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                post("/api/v1/demands"),
                post("/api/v1/demands").header("Idempotency-Key", "not-a-uuid"))) {
            mockMvc.perform(request.principal(customer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"INSPECTION\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }
        verifyNoInteractions(service);
    }

    @Test
    void invalidContentIsABadRequest() throws Exception {
        when(service.create(any(), any(), any(), any(), eq(false)))
                .thenThrow(new IllegalArgumentException("deliverable does not fit the demand type"));

        mockMvc.perform(post("/api/v1/demands").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"INSPECTION\",\"deliverables\":[\"PHOTOS\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void anIncompleteSubmissionNamesTheFirstMissingField() throws Exception {
        when(service.submit(SUBJECT, DEMAND)).thenThrow(new DemandException(Error.INCOMPLETE, "scope"));

        mockMvc.perform(post("/api/v1/demands/" + DEMAND + "/submit").principal(customer))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEMAND_INCOMPLETE"))
                .andExpect(jsonPath("$.message").value("请填写作业范围"))
                .andExpect(jsonPath("$.details.field").value("scope"));
    }

    @Test
    void draftsAreUpdatedSubmittedReadListedCancelledAndDeleted() throws Exception {
        when(service.updateDraft(eq(SUBJECT), eq(DEMAND), any())).thenReturn(demand(DemandStatus.DRAFT, 3));
        when(service.submit(SUBJECT, DEMAND)).thenReturn(demand(DemandStatus.OPEN, 4));
        when(service.read(SUBJECT, DEMAND)).thenReturn(demand(DemandStatus.QUOTED, 5));
        when(service.cancel(SUBJECT, DEMAND)).thenReturn(demand(DemandStatus.CLOSED, 6));
        when(service.list(SUBJECT, "QUOTED", 2, 10))
                .thenReturn(new DemandPageView(2, 10, 11, false, List.of(demand(DemandStatus.QUOTED, 5))));
        when(service.list(SUBJECT, null, 1, 20)).thenReturn(new DemandPageView(1, 20, 0, false, List.of()));

        mockMvc.perform(put("/api/v1/demands/" + DEMAND).principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SURVEY\",\"deliverables\":[\"POINT_CLOUD\"]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""));
        verify(service).updateDraft(SUBJECT, DEMAND, new DemandDraft("SURVEY", null, null, null, null, null, null,
                List.of("POINT_CLOUD"), null, null, null));
        mockMvc.perform(post("/api/v1/demands/" + DEMAND + "/submit").principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""));
        mockMvc.perform(get("/api/v1/demands/" + DEMAND).principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"5\""))
                .andExpect(jsonPath("$.status").value("QUOTED"));
        mockMvc.perform(post("/api/v1/demands/" + DEMAND + "/cancel").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closeReason").value("CANCELLED"));
        mockMvc.perform(get("/api/v1/demands?status=QUOTED&page=2&size=10").principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.items[0].status").value("QUOTED"));
        mockMvc.perform(get("/api/v1/demands").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(delete("/api/v1/demands/" + DEMAND).principal(customer))
                .andExpect(status().isNoContent());
        verify(service).deleteDraft(SUBJECT, DEMAND);
    }

    @Test
    void quotesAreListedWithTheRequestedSort() throws Exception {
        when(service.quotes(SUBJECT, DEMAND, "PRICE")).thenReturn(new DemandQuoteListView(DEMAND,
                DemandStatus.QUOTED, 5, DemandQuoteSort.PRICE, List.of(quote())));

        mockMvc.perform(get("/api/v1/demands/" + DEMAND + "/quotes?sort=PRICE").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demandVersion").value(5))
                .andExpect(jsonPath("$.sort").value("PRICE"))
                .andExpect(jsonPath("$.items[0].length()").value(19))
                .andExpect(jsonPath("$.items[0].quoteId").value(QUOTE.toString()))
                .andExpect(jsonPath("$.items[0].provider.name").value("云翼科技"))
                .andExpect(jsonPath("$.items[0].provider.certificationLabel").value("企业认证"))
                .andExpect(jsonPath("$.items[0].provider.qualificationReviewed").value(true))
                .andExpect(jsonPath("$.items[0].version").value(2))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].amountMinor").value(1_860_000))
                .andExpect(jsonPath("$.items[0].schedule").value("共 6 次 · 11/02 首次进场"))
                .andExpect(jsonPath("$.items[0].durationDays").value(90))
                .andExpect(jsonPath("$.items[0].choosable").value(true));
        mockMvc.perform(get("/api/v1/demands/" + DEMAND + "/quotes").principal(customer));
        verify(service).quotes(SUBJECT, DEMAND, null);
    }

    @Test
    void choosingPassesTheIfMatchVersionAndTheQuoteVersion() throws Exception {
        when(service.choose(SUBJECT, DEMAND, 5, QUOTE, 2)).thenReturn(demand(DemandStatus.CHOSEN, 6));

        for (String ifMatch : List.of("\"5\"", "W/\"5\"", "5")) {
            mockMvc.perform(post("/api/v1/demands/" + DEMAND + "/choice").principal(customer)
                            .header("If-Match", ifMatch)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quoteId\":\"" + QUOTE + "\",\"quoteVersion\":2}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"6\""))
                    .andExpect(jsonPath("$.chosen.quoteId").value(QUOTE.toString()))
                    .andExpect(jsonPath("$.chosen.quoteVersion").value(2))
                    .andExpect(jsonPath("$.chosen.providerName").value("云翼科技"))
                    .andExpect(jsonPath("$.chosen.amountMinor").value(1_860_000));
        }
        for (MockHttpServletRequestBuilder request : List.of(
                post("/api/v1/demands/" + DEMAND + "/choice"),
                post("/api/v1/demands/" + DEMAND + "/choice").header("If-Match", "\"v5\""),
                post("/api/v1/demands/" + DEMAND + "/choice").header("If-Match", "-1"))) {
            mockMvc.perform(request.principal(customer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quoteId\":\"" + QUOTE + "\",\"quoteVersion\":2}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }
    }

    @ParameterizedTest
    @EnumSource(Error.class)
    void everyBusinessRejectionHasItsStatusAndCode(Error error) throws Exception {
        when(service.choose(eq(SUBJECT), eq(DEMAND), anyInt(), eq(QUOTE), eq(2)))
                .thenThrow(new DemandException(error, error == Error.INCOMPLETE ? "address" : null));
        Rejection expected = REJECTIONS.get(error);

        mockMvc.perform(post("/api/v1/demands/" + DEMAND + "/choice").principal(customer)
                        .header("If-Match", "\"5\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":\"" + QUOTE + "\",\"quoteVersion\":2}"))
                .andExpect(status().is(expected.status()))
                .andExpect(jsonPath("$.code").value(expected.code()))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ---- 服务商 ----

    @Test
    void providersQuoteAsTheirOrganizationAndNeverSeeThePublisher() throws Exception {
        OffsetDateTime validUntil = OffsetDateTime.parse("2026-11-26T02:00:00Z");
        when(service.submitQuote(PROVIDER, OPERATOR, DEMAND, new QuoteDraft(1_860_000L, "无人机 + 机器狗",
                "共 6 次 · 11/02 首次进场", 90, "巡检报告、隐患清单", validUntil))).thenReturn(providerDemand());

        mockMvc.perform(put("/api/v1/provider/demands/" + DEMAND + "/quote").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amountMinor":1860000,"method":"无人机 + 机器狗","schedule":"共 6 次 · 11/02 首次进场",
                                 "durationDays":90,"deliverables":"巡检报告、隐患清单","validUntil":"2026-11-26T10:00:00+08:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.length()").value(19))
                .andExpect(jsonPath("$.publisher").doesNotExist())
                .andExpect(jsonPath("$.invited").value(false))
                .andExpect(jsonPath("$.myQuote.version").value(2))
                .andExpect(jsonPath("$.myQuote.status").value("ACTIVE"));
    }

    @Test
    void providersListReadAndWithdraw() throws Exception {
        when(service.listForProvider(PROVIDER, null, 1, 20))
                .thenReturn(new ProviderDemandPageView(1, 20, 1, false, List.of(providerDemand())));
        when(service.listForProvider(PROVIDER, "QUOTED", 2, 10))
                .thenReturn(new ProviderDemandPageView(2, 10, 0, false, List.of()));
        when(service.readForProvider(PROVIDER, DEMAND)).thenReturn(providerDemand());
        when(service.withdrawQuote(PROVIDER, OPERATOR, DEMAND)).thenReturn(providerDemand());

        mockMvc.perform(get("/api/v1/provider/demands").principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].demandId").value(DEMAND.toString()));
        // 1.2.0：工作台按状态筛。
        mockMvc.perform(get("/api/v1/provider/demands").principal(operator)
                        .param("status", "QUOTED").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        verify(service).listForProvider(PROVIDER, "QUOTED", 2, 10);
        mockMvc.perform(get("/api/v1/provider/demands/" + DEMAND).principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("东营经济技术开发区"));
        mockMvc.perform(delete("/api/v1/provider/demands/" + DEMAND + "/quote").principal(operator))
                .andExpect(status().isOk());
        verify(service).withdrawQuote(PROVIDER, OPERATOR, DEMAND);
        doThrow(new DemandException(Error.QUOTING_CLOSED)).when(service)
                .submitQuote(eq(PROVIDER), eq(OPERATOR), eq(DEMAND), any());
        mockMvc.perform(put("/api/v1/provider/demands/" + DEMAND + "/quote").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEMAND_QUOTING_CLOSED"));
    }

    // ---- 数据 ----

    private static Stream<Arguments> customerRoutes() {
        String demand = "/api/v1/demands/" + DEMAND;
        return Stream.of(
                Arguments.of("POST demands", post("/api/v1/demands")
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"INSPECTION\"}")),
                Arguments.of("GET demands", get("/api/v1/demands")),
                Arguments.of("GET demand", get(demand)),
                Arguments.of("PUT demand", put(demand).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"INSPECTION\"}")),
                Arguments.of("DELETE demand", delete(demand)),
                Arguments.of("POST submit", post(demand + "/submit")),
                Arguments.of("POST cancel", post(demand + "/cancel")),
                Arguments.of("GET quotes", get(demand + "/quotes")),
                Arguments.of("POST choice", post(demand + "/choice")
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":\"" + QUOTE + "\",\"quoteVersion\":1}")));
    }

    private static Stream<Arguments> providerRoutes() {
        String demand = "/api/v1/provider/demands/" + DEMAND;
        return Stream.of(
                Arguments.of("GET provider demands", get("/api/v1/provider/demands")),
                Arguments.of("GET provider demand", get(demand)),
                Arguments.of("PUT quote", put(demand + "/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountMinor\":1}")),
                Arguments.of("DELETE quote", delete(demand + "/quote")));
    }

    private static Stream<Arguments> allRoutes() {
        return Stream.of(customerRoutes(), providerRoutes(),
                        Stream.of(Arguments.of("GET demand-providers", get("/api/v1/demand-providers?cityCode=370500"))))
                .flatMap(stream -> stream);
    }

    private static DemandView demand(DemandStatus status, int version) {
        boolean chosen = status == DemandStatus.CHOSEN;
        return new DemandView(
                DEMAND,
                status,
                status == DemandStatus.CLOSED ? DemandCloseReason.CANCELLED : null,
                DemandType.INSPECTION,
                "巡检需求",
                new DemandPublisherView(DemandPublisherKind.ORGANIZATION, "东营示例物流公司"),
                "370500",
                "东营市",
                "东营经济技术开发区",
                "约 1.2 平方公里",
                LocalDate.parse("2026-11-30"),
                1_000_000L,
                2_000_000L,
                List.of(new DemandDeliverableView(DemandDeliverable.INSPECTION_REPORT, "巡检报告")),
                null,
                DemandQuoteScope.INVITE,
                List.of(new DemandProviderView(PROVIDER, "云翼科技", "企业认证", null, true)),
                status == DemandStatus.DRAFT ? null : NOW,
                status == DemandStatus.DRAFT ? null : NOW.plusHours(72),
                status == DemandStatus.OPEN || status == DemandStatus.QUOTED,
                status == DemandStatus.QUOTED || chosen ? 1 : 0,
                chosen ? new DemandChosenView(QUOTE, 2, "云翼科技", 1_860_000, NOW) : null,
                status == DemandStatus.DRAFT,
                status == DemandStatus.OPEN || status == DemandStatus.QUOTED,
                status == DemandStatus.DRAFT,
                NOW,
                NOW,
                version,
                null,
                null);
    }

    private static DemandQuoteView quote() {
        return new DemandQuoteView(QUOTE, new DemandQuoteProviderView(PROVIDER, "云翼科技", "企业认证", true), 2,
                DemandQuoteStatus.ACTIVE, 1_860_000, "无人机 + 机器狗", "共 6 次 · 11/02 首次进场", 90,
                "巡检报告、隐患清单", NOW.plusDays(7), NOW, NOW.plusHours(1), true,
                List.of(new DemandQuoteLineView("无人机巡检", 6, "次", 180_000L, 1_080_000L),
                        new DemandQuoteLineView("报告与隐患跟踪", null, null, null, 780_000L)),
                "东营本地", "机器狗夜巡", 48, 90, LocalDate.parse("2026-11-25"));
    }

    private static ProviderDemandView providerDemand() {
        return new ProviderDemandView(DEMAND, DemandStatus.QUOTED, DemandType.INSPECTION, "巡检需求", "370500", "东营市",
                "东营经济技术开发区", "约 1.2 平方公里", LocalDate.parse("2026-11-30"), 1_000_000L, 2_000_000L,
                List.of(new DemandDeliverableView(DemandDeliverable.INSPECTION_REPORT, "巡检报告")), null, false,
                NOW, NOW.plusHours(72), true,
                new ProviderQuoteView(QUOTE, 2, DemandQuoteStatus.ACTIVE, 1_860_000, "无人机 + 机器狗",
                        "共 6 次 · 11/02 首次进场", 90, "巡检报告、隐患清单", NOW.plusDays(7), NOW, NOW.plusHours(1),
                        List.of(), null, null, null, null, null), null);
    }

    private static TestingAuthenticationToken signedIn(UUID subjectId, UUID organizationId, String role) {
        return new TestingAuthenticationToken(
                new CurrentSubject(subjectId, Set.of(role), organizationId, Set.of(), 0), null);
    }

    private record Rejection(int status, String code) {
    }
}
