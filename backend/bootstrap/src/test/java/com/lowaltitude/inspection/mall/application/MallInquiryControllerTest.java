package com.lowaltitude.inspection.mall.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.mall.application.MallInquiryException.Error;
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
 * 商城询价的两个控制器（F4）：从令牌里取账号、角色和机构，把请求转成服务的参数，把结果、ETag 和业务拒绝转成契约里的状态码。
 * 没带令牌时的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里检查。
 */
class MallInquiryControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ORG = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID OPERATOR = UUID.fromString("c1000000-0000-4000-8000-0000000000a1");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000203");
    private static final UUID OFFER = UUID.fromString("a2030000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OFFER = UUID.fromString("a2030000-0000-4000-8000-000000000005");
    private static final UUID INQUIRY = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final UUID QUOTE = UUID.fromString("e4000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-19T02:00:00Z");
    private static final MallMerchantView ROBOT = new MallMerchantView(MERCHANT, "示例机器人商家",
            MallMerchantKind.FACTORY_DIRECT);

    /** 每一种业务拒绝对外的状态码和错误码（GlobalExceptionHandler 与契约 1.0.0）。 */
    private static final Map<Error, Rejection> REJECTIONS = Map.ofEntries(
            Map.entry(Error.NOT_FOUND, new Rejection(404, "COMMON_NOT_FOUND")),
            Map.entry(Error.FORBIDDEN, new Rejection(403, "COMMON_FORBIDDEN")),
            Map.entry(Error.IDEMPOTENCY_CONFLICT, new Rejection(409, "MALL_INQUIRY_IDEMPOTENCY_KEY_REUSED")),
            Map.entry(Error.OFFER_UNAVAILABLE, new Rejection(409, "MALL_INQUIRY_OFFER_UNAVAILABLE")),
            Map.entry(Error.MERCHANT_UNAVAILABLE, new Rejection(409, "MALL_INQUIRY_MERCHANT_UNAVAILABLE")),
            Map.entry(Error.STATE_INVALID, new Rejection(409, "MALL_INQUIRY_STATE_INVALID")),
            Map.entry(Error.QUOTING_CLOSED, new Rejection(409, "MALL_INQUIRY_QUOTING_CLOSED")),
            Map.entry(Error.VERSION_CONFLICT, new Rejection(412, "MALL_INQUIRY_VERSION_CONFLICT")),
            Map.entry(Error.QUOTE_CHANGED, new Rejection(409, "MALL_INQUIRY_QUOTE_CHANGED")),
            Map.entry(Error.QUOTE_EXPIRED, new Rejection(409, "MALL_INQUIRY_QUOTE_EXPIRED")),
            Map.entry(Error.QUOTE_NOT_CHOSEN, new Rejection(409, "MALL_INQUIRY_QUOTE_NOT_CHOSEN")));

    private final MallInquiryService service = mock(MallInquiryService.class);
    private final IdentityProfilePort profiles = mock(IdentityProfilePort.class);
    private final TestingAuthenticationToken customer = signedIn(SUBJECT, CUSTOMER_ORG, "CUSTOMER");
    private final TestingAuthenticationToken operator = signedIn(OPERATOR, MERCHANT, "PROVIDER_OPERATOR");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MallInquiryController(service, profiles),
                        new MallInquiryMerchantController(service))
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
    @MethodSource("merchantRoutes")
    void merchantRoutesNeedAProviderOperatorOfAnOrganization(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        for (TestingAuthenticationToken who : List.of(customer, signedIn(OPERATOR, null, "PROVIDER_OPERATOR"))) {
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

    // ---- 客户 ----

    @Test
    void theOffersOfTheProductInTheCityAreListed() throws Exception {
        when(service.offers(PRODUCT, "370500")).thenReturn(new MallInquiryOfferListView(PRODUCT, "370500", List.of(
                new MallInquiryOfferView(OFFER, ROBOT, "按配置报价", true),
                new MallInquiryOfferView(OTHER_OFFER, new MallMerchantView(UUID.randomUUID(), "示例无人机厂家",
                        MallMerchantKind.FACTORY_DIRECT), null, false))));

        mockMvc.perform(get("/api/v1/mall/inquiry-offers?productId=" + PRODUCT + "&cityCode=370500").principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].merchant.kind").value("FACTORY_DIRECT"))
                .andExpect(jsonPath("$.items[0].canInquire").value(true))
                .andExpect(jsonPath("$.items[1].canInquire").value(false));
    }

    @Test
    void submittingRecordsTheBuyerAndAnswers201WithAnETag() throws Exception {
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.of(
                new IdentityProfile(SUBJECT, "王女士", "东营港务物流", "CUSTOMER")));
        MallInquiryDraft draft = new MallInquiryDraft(PRODUCT, "测绘版 · RTK", 2, "370500", "2026-12-15",
                List.of(OFFER, OTHER_OFFER));
        when(service.submit(SUBJECT, new MallInquiryBuyer(CUSTOMER_ORG, "东营港务物流", "王女士"), KEY, draft))
                .thenReturn(new MallInquiryCreated(inquiry(MallInquiryStatus.OPEN, 0, null), true));

        mockMvc.perform(post("/api/v1/mall/inquiries").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId":"%s","requirement":"测绘版 · RTK","quantity":2,"cityCode":"370500",
                                 "expectedDate":"2026-12-15","offerIds":["%s","%s"]}
                                """.formatted(PRODUCT, OFFER, OTHER_OFFER)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.length()").value(18))
                .andExpect(jsonPath("$.inquiryId").value(INQUIRY.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.buyer.kind").value("ORGANIZATION"))
                .andExpect(jsonPath("$.buyer.organizationName").value("东营港务物流"))
                .andExpect(jsonPath("$.product.coverImage.url").value("https://media.example.com/drone.jpg"))
                .andExpect(jsonPath("$.expectedDate").value("2026-12-15"))
                .andExpect(jsonPath("$.merchants[0].status").value("INVITED"))
                .andExpect(jsonPath("$.quoteDeadline").value("2026-11-22T02:00:00Z"))
                .andExpect(jsonPath("$.chosen").doesNotExist());
    }

    @Test
    void aPersonalAccountAsksAsItselfAndAReplayAnswers200() throws Exception {
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.of(
                new IdentityProfile(SUBJECT, "王女士", "云翼科技", "PROVIDER")));
        when(service.submit(eq(SUBJECT), eq(new MallInquiryBuyer(null, null, "王女士")), eq(KEY), any()))
                .thenReturn(new MallInquiryCreated(inquiry(MallInquiryStatus.OPEN, 0, null), false));
        String body = "{\"productId\":\"" + PRODUCT + "\"}";

        mockMvc.perform(post("/api/v1/mall/inquiries").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""));
        // 没有登录资料：当作无权限。
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/mall/inquiries").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/mall/inquiries").principal(customer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void anInvalidInquiryIsABadRequest() throws Exception {
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.of(
                new IdentityProfile(SUBJECT, "王女士", "东营港务物流", "CUSTOMER")));
        when(service.submit(eq(SUBJECT), any(), eq(KEY), any())).thenThrow(new IllegalArgumentException("offers"));

        mockMvc.perform(post("/api/v1/mall/inquiries").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"offerIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void inquiriesAreReadWithTheirQuotesListedAndClosedWithTheirETag() throws Exception {
        MallInquiryView quoted = inquiry(MallInquiryStatus.QUOTED, 3, null);
        when(service.detail(SUBJECT, INQUIRY, "PRICE")).thenReturn(new MallInquiryDetailView(quoted,
                MallInquirySort.PRICE, List.of(quote(MallInquiryQuoteStatus.ACTIVE, true))));
        when(service.close(SUBJECT, INQUIRY)).thenReturn(inquiry(MallInquiryStatus.CLOSED, 4, null));
        when(service.list(SUBJECT, "QUOTED", 2, 10)).thenReturn(new MallInquiryPageView(2, 10, 11, false,
                List.of(quoted)));

        mockMvc.perform(get("/api/v1/mall/inquiries/" + INQUIRY + "?sort=PRICE").principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.sort").value("PRICE"))
                .andExpect(jsonPath("$.inquiry.status").value("QUOTED"))
                .andExpect(jsonPath("$.quotes[0].length()").value(14))
                .andExpect(jsonPath("$.quotes[0].version").value(2))
                .andExpect(jsonPath("$.quotes[0].unitAmountMinor").value(2880000))
                .andExpect(jsonPath("$.quotes[0].totalMinor").value(5760000))
                .andExpect(jsonPath("$.quotes[0].model").value("测绘版 · RTK"))
                .andExpect(jsonPath("$.quotes[0].leadDays").value(12))
                .andExpect(jsonPath("$.quotes[0].validUntil").value("2026-11-22T02:00:00Z"))
                .andExpect(jsonPath("$.quotes[0].choosable").value(true));
        mockMvc.perform(post("/api/v1/mall/inquiries/" + INQUIRY + "/close").principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closeReason").value("CUSTOMER"));
        mockMvc.perform(get("/api/v1/mall/inquiries?status=QUOTED&page=2&size=10").principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.items[0].quoteCount").value(1));
    }

    @Test
    void choosingPassesTheIfMatchVersionAndTheQuoteVersion() throws Exception {
        MallInquiryChosenView chosen = new MallInquiryChosenView(QUOTE, 2, OFFER, ROBOT, 2880000, 2, 5760000,
                NOW.plusDays(3), NOW, null);
        when(service.choose(SUBJECT, INQUIRY, 3, QUOTE, 2)).thenReturn(inquiry(MallInquiryStatus.CHOSEN, 4, chosen));

        for (String ifMatch : List.of("\"3\"", "W/\"3\"", "3")) {
            mockMvc.perform(post("/api/v1/mall/inquiries/" + INQUIRY + "/choice").principal(customer)
                            .header("If-Match", ifMatch)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quoteId\":\"" + QUOTE + "\",\"quoteVersion\":2}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("ETag", "\"4\""))
                    .andExpect(jsonPath("$.status").value("CHOSEN"))
                    .andExpect(jsonPath("$.chosen.length()").value(10))
                    .andExpect(jsonPath("$.chosen.quoteId").value(QUOTE.toString()))
                    .andExpect(jsonPath("$.chosen.merchant.name").value("示例机器人商家"))
                    .andExpect(jsonPath("$.chosen.totalMinor").value(5760000))
                    .andExpect(jsonPath("$.chosen.orderId").doesNotExist());
        }
        for (String ifMatch : List.of("", "abc", "\"-1\"")) {
            mockMvc.perform(post("/api/v1/mall/inquiries/" + INQUIRY + "/choice").principal(customer)
                            .header("If-Match", ifMatch)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quoteId\":\"" + QUOTE + "\",\"quoteVersion\":2}"))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/v1/mall/inquiries/" + INQUIRY + "/choice").principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":\"" + QUOTE + "\",\"quoteVersion\":2}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @EnumSource(Error.class)
    void everyBusinessRejectionHasItsStatusAndCode(Error error) throws Exception {
        when(service.choose(eq(SUBJECT), eq(INQUIRY), anyInt(), eq(QUOTE), eq(2))).thenThrow(new MallInquiryException(error));
        Rejection expected = REJECTIONS.get(error);

        mockMvc.perform(post("/api/v1/mall/inquiries/" + INQUIRY + "/choice").principal(customer)
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":\"" + QUOTE + "\",\"quoteVersion\":2}"))
                .andExpect(status().is(expected.status()))
                .andExpect(jsonPath("$.code").value(expected.code()))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // ---- 商家 ----

    @Test
    void merchantsQuoteAndDeclineAsTheirOrganizationAndNeverSeeTheBuyer() throws Exception {
        OffsetDateTime validUntil = OffsetDateTime.parse("2026-11-22T02:00:00Z");
        MallInquiryQuoteDraft draft = new MallInquiryQuoteDraft(2880000L, "测绘版 · RTK", 12, "含 2 名飞手培训",
                validUntil);
        when(service.quote(MERCHANT, OPERATOR, INQUIRY, draft)).thenReturn(merchantInquiry(
                MallInquiryInvitationStatus.QUOTED, null));
        when(service.decline(MERCHANT, OPERATOR, INQUIRY, "缺货")).thenReturn(merchantInquiry(
                MallInquiryInvitationStatus.DECLINED, "缺货"));
        when(service.merchantInquiries(MERCHANT, "OPEN", 1, 20)).thenReturn(new MallMerchantInquiryPageView(1, 20, 1,
                false, List.of(merchantInquiry(MallInquiryInvitationStatus.INVITED, null))));
        when(service.merchantInquiry(MERCHANT, INQUIRY)).thenReturn(merchantInquiry(MallInquiryInvitationStatus.INVITED,
                null));

        mockMvc.perform(put("/api/v1/provider/mall/inquiries/" + INQUIRY + "/quote").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"unitAmountMinor":2880000,"model":"测绘版 · RTK","leadDays":12,"includes":"含 2 名飞手培训",
                                 "validUntil":"2026-11-22T10:00:00+08:00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.length()").value(14))
                .andExpect(jsonPath("$.invitationStatus").value("QUOTED"))
                .andExpect(jsonPath("$.myQuote.version").value(2))
                .andExpect(jsonPath("$.myQuote.totalMinor").value(5760000))
                .andExpect(jsonPath("$.buyer").doesNotExist());
        mockMvc.perform(post("/api/v1/provider/mall/inquiries/" + INQUIRY + "/decline").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"缺货\"}"))
                .andExpect(jsonPath("$.invitationStatus").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason").value("缺货"));
        mockMvc.perform(get("/api/v1/provider/mall/inquiries?status=OPEN").principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].requirement").value("测绘版 · RTK"));
        mockMvc.perform(get("/api/v1/provider/mall/inquiries/" + INQUIRY).principal(operator))
                .andExpect(jsonPath("$.invitationStatus").value("INVITED"))
                .andExpect(jsonPath("$.myQuote").doesNotExist());
        verify(service).decline(MERCHANT, OPERATOR, INQUIRY, "缺货");
    }

    @Test
    void aQuoteRejectionFromTheServiceIsMapped() throws Exception {
        when(service.quote(eq(MERCHANT), eq(OPERATOR), eq(INQUIRY), any()))
                .thenThrow(new MallInquiryException(Error.QUOTING_CLOSED));

        mockMvc.perform(put("/api/v1/provider/mall/inquiries/" + INQUIRY + "/quote").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unitAmountMinor\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MALL_INQUIRY_QUOTING_CLOSED"));
    }

    // ---- 数据 ----

    private static Stream<Arguments> customerRoutes() {
        String inquiry = "/api/v1/mall/inquiries/" + INQUIRY;
        return Stream.of(
                Arguments.of("GET inquiry-offers", get("/api/v1/mall/inquiry-offers?productId=" + PRODUCT
                        + "&cityCode=370500")),
                Arguments.of("POST inquiries", post("/api/v1/mall/inquiries").header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"productId\":\"" + PRODUCT + "\"}")),
                Arguments.of("GET inquiries", get("/api/v1/mall/inquiries")),
                Arguments.of("GET inquiry", get(inquiry)),
                Arguments.of("POST close", post(inquiry + "/close")),
                Arguments.of("POST choice", post(inquiry + "/choice").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteId\":\"" + QUOTE + "\",\"quoteVersion\":1}")));
    }

    private static Stream<Arguments> merchantRoutes() {
        String inquiry = "/api/v1/provider/mall/inquiries/" + INQUIRY;
        return Stream.of(
                Arguments.of("GET provider inquiries", get("/api/v1/provider/mall/inquiries")),
                Arguments.of("GET provider inquiry", get(inquiry)),
                Arguments.of("PUT quote", put(inquiry + "/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitAmountMinor\":1}")),
                Arguments.of("POST decline", post(inquiry + "/decline").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"缺货\"}")));
    }

    private static Stream<Arguments> allRoutes() {
        return Stream.concat(customerRoutes(), merchantRoutes());
    }

    private static MallInquiryView inquiry(MallInquiryStatus status, int version, MallInquiryChosenView chosen) {
        boolean open = status == MallInquiryStatus.OPEN || status == MallInquiryStatus.QUOTED;
        return new MallInquiryView(INQUIRY, status, status == MallInquiryStatus.CLOSED ? MallInquiryCloseReason.CUSTOMER : null,
                new MallInquiryBuyerView(MallInquiryBuyerKind.ORGANIZATION, "东营港务物流", "王女士"),
                new MallInquiryProductView(PRODUCT, "行业测绘无人机",
                        new MallMediaView("https://media.example.com/drone.jpg", "行业测绘无人机")),
                "测绘版 · RTK", 2, "370500", LocalDate.parse("2026-12-15"),
                List.of(new MallInquiryMerchantView(OFFER, ROBOT, MallInquiryInvitationStatus.INVITED, null)),
                NOW, NOW.plusHours(72), open, status == MallInquiryStatus.OPEN ? 0 : 1, chosen, open, NOW, version);
    }

    private static MallInquiryQuoteView quote(MallInquiryQuoteStatus status, boolean choosable) {
        return new MallInquiryQuoteView(QUOTE, 2, OFFER, ROBOT, status, 2880000, 2, 5760000, "测绘版 · RTK", 12,
                "含 2 名飞手培训", NOW.plusDays(3), NOW, choosable);
    }

    private static MallMerchantInquiryView merchantInquiry(MallInquiryInvitationStatus invitation, String reason) {
        MallMerchantQuoteView myQuote = invitation == MallInquiryInvitationStatus.INVITED ? null
                : new MallMerchantQuoteView(QUOTE, 2, invitation == MallInquiryInvitationStatus.DECLINED
                        ? MallInquiryQuoteStatus.WITHDRAWN : MallInquiryQuoteStatus.ACTIVE, 2880000, 2, 5760000,
                        "测绘版 · RTK", 12, "含 2 名飞手培训", NOW.plusDays(3), NOW);
        return new MallMerchantInquiryView(INQUIRY, MallInquiryStatus.QUOTED,
                new MallInquiryProductView(PRODUCT, "行业测绘无人机", null), OFFER, "测绘版 · RTK", 2, "370500",
                LocalDate.parse("2026-12-15"), NOW, NOW.plusHours(72), invitation != MallInquiryInvitationStatus.DECLINED,
                invitation, reason, myQuote);
    }

    private static TestingAuthenticationToken signedIn(UUID subjectId, UUID organizationId, String role) {
        return new TestingAuthenticationToken(
                new CurrentSubject(subjectId, Set.of(role), organizationId, Set.of(), 0), null);
    }

    private record Rejection(int status, String code) {
    }
}
