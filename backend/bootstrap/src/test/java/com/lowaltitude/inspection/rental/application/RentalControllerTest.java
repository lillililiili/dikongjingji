package com.lowaltitude.inspection.rental.application;

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
import com.lowaltitude.inspection.rental.application.RentalException.Error;
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
 * 租赁的两个控制器（F3）：从令牌里取账号、角色和机构，把请求转成服务的参数，把结果、ETag 和业务拒绝转成契约里的状态码。
 * 没带令牌时的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里逐个接口检查。
 */
class RentalControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CUSTOMER_ORG = UUID.fromString("11111111-1111-1111-1111-aaaaaaaaaaaa");
    private static final UUID OPERATOR = UUID.fromString("c1000000-0000-4000-8000-0000000000a1");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID INQUIRY = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("e2000000-0000-4000-8000-000000000001");
    private static final UUID TRADE = UUID.fromString("e3000000-0000-4000-8000-000000000001");
    private static final UUID OFFER = UUID.fromString("7344eb2d-bf1d-aa17-7e27-fb2351e99dee");
    private static final UUID ADDRESS = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-19T02:00:00Z");

    /** 每一种业务拒绝对外的状态码和错误码（GlobalExceptionHandler 与契约 1.0.0）。 */
    private static final Map<Error, Rejection> REJECTIONS = Map.ofEntries(
            Map.entry(Error.NOT_FOUND, new Rejection(404, "COMMON_NOT_FOUND")),
            Map.entry(Error.FORBIDDEN, new Rejection(403, "COMMON_FORBIDDEN")),
            Map.entry(Error.IDEMPOTENCY_CONFLICT, new Rejection(409, "RENTAL_IDEMPOTENCY_KEY_REUSED")),
            Map.entry(Error.OFFER_UNAVAILABLE, new Rejection(409, "RENTAL_OFFER_UNAVAILABLE")),
            Map.entry(Error.PAYMENT_UNAVAILABLE, new Rejection(409, "RENTAL_PAYMENT_UNAVAILABLE")),
            Map.entry(Error.ADDRESS_NOT_FOUND, new Rejection(422, "RENTAL_ADDRESS_NOT_FOUND")),
            Map.entry(Error.STATE_INVALID, new Rejection(409, "RENTAL_STATE_INVALID")),
            Map.entry(Error.VERSION_CONFLICT, new Rejection(412, "RENTAL_VERSION_CONFLICT")),
            Map.entry(Error.QUOTE_CHANGED, new Rejection(409, "RENTAL_QUOTE_CHANGED")),
            Map.entry(Error.QUOTE_EXPIRED, new Rejection(409, "RENTAL_QUOTE_EXPIRED")),
            Map.entry(Error.SCHEDULE_UNAVAILABLE, new Rejection(409, "RENTAL_SCHEDULE_UNAVAILABLE")),
            Map.entry(Error.START_DATE_PASSED, new Rejection(409, "RENTAL_START_DATE_PASSED")),
            Map.entry(Error.TERMS_NOT_AGREED, new Rejection(422, "RENTAL_TERMS_NOT_AGREED")),
            Map.entry(Error.REFUND_NOT_ALLOWED, new Rejection(409, "RENTAL_REFUND_NOT_ALLOWED")));

    private final RentalService service = mock(RentalService.class);
    private final IdentityProfilePort profiles = mock(IdentityProfilePort.class);
    private final TestingAuthenticationToken customer = signedIn(SUBJECT, CUSTOMER_ORG, "CUSTOMER");
    private final TestingAuthenticationToken operator = signedIn(OPERATOR, MERCHANT, "PROVIDER_OPERATOR");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new RentalController(service, profiles),
                        new RentalMerchantController(service))
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
    void submittingRecordsTheLesseeAndAnswers201WithAnETag() throws Exception {
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.of(
                new IdentityProfile(SUBJECT, "王女士", "东营港务物流", "CUSTOMER")));
        RentalInquiryDraft draft = new RentalInquiryDraft(OFFER, 1, "2026-11-22", 5, "DELIVERY", "COLLECT", ADDRESS,
                "夜间巡逻");
        when(service.submit(SUBJECT, new RentalLessee(CUSTOMER_ORG, "东营港务物流", "王女士"), KEY, draft))
                .thenReturn(new RentalInquiryCreated(inquiry(RentalInquiryStatus.PENDING, 0, false), true));

        mockMvc.perform(post("/api/v1/rental-inquiries").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"offerId":"%s","quantity":1,"startDate":"2026-11-22","days":5,"handoverMode":"DELIVERY",
                                 "returnMode":"COLLECT","addressId":"%s","note":"夜间巡逻"}
                                """.formatted(OFFER, ADDRESS)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.length()").value(23))
                .andExpect(jsonPath("$.inquiryId").value(INQUIRY.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.lessee.kind").value("ORGANIZATION"))
                .andExpect(jsonPath("$.item.referenceAmountMinor").value(38000))
                .andExpect(jsonPath("$.startDate").value("2026-11-22"))
                .andExpect(jsonPath("$.handoverMode").value("DELIVERY"))
                .andExpect(jsonPath("$.place.phone").value("13800001234"))
                .andExpect(jsonPath("$.quote").doesNotExist());
    }

    @Test
    void aPersonalAccountRentsAsItselfAndAReplayAnswers200() throws Exception {
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.of(
                new IdentityProfile(SUBJECT, "王女士", "云翼科技", "PROVIDER")));
        when(service.submit(eq(SUBJECT), eq(new RentalLessee(null, null, "王女士")), eq(KEY), any()))
                .thenReturn(new RentalInquiryCreated(inquiry(RentalInquiryStatus.PENDING, 0, false), false));

        mockMvc.perform(post("/api/v1/rental-inquiries").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"" + OFFER + "\"}"))
                .andExpect(status().isOk());
        // 没有登录资料：当作无权限。
        when(profiles.findProfile(SUBJECT)).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/rental-inquiries").principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"" + OFFER + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/rental-inquiries").principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"" + OFFER + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void inquiriesAreReadListedAndClosedWithTheirETag() throws Exception {
        when(service.readInquiry(SUBJECT, INQUIRY)).thenReturn(inquiry(RentalInquiryStatus.QUOTED, 3, true));
        when(service.closeInquiry(SUBJECT, INQUIRY)).thenReturn(inquiry(RentalInquiryStatus.CLOSED, 4, false));
        when(service.listInquiries(SUBJECT, "QUOTED", 2, 10)).thenReturn(new RentalInquiryPageView(2, 10, 11, false,
                List.of(inquiry(RentalInquiryStatus.QUOTED, 3, true))));

        mockMvc.perform(get("/api/v1/rental-inquiries/" + INQUIRY).principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.quote.version").value(2))
                .andExpect(jsonPath("$.quote.fees[0].name").value("送达与取回"))
                .andExpect(jsonPath("$.quote.fees[0].amountMinor").value(0))
                .andExpect(jsonPath("$.quote.totalMinor").value(195000))
                .andExpect(jsonPath("$.confirmable").value(true));
        mockMvc.perform(post("/api/v1/rental-inquiries/" + INQUIRY + "/close").principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.status").value("CLOSED"));
        mockMvc.perform(get("/api/v1/rental-inquiries?status=QUOTED&page=2&size=10").principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.total").value(11));
    }

    @Test
    void confirmingPassesTheIfMatchVersionTheQuoteVersionAndTheAgreement() throws Exception {
        when(service.confirm(SUBJECT, INQUIRY, 3, 2, true))
                .thenReturn(new RentalConfirmationView(INQUIRY, ORDER, TRADE, NOW.plusMinutes(30)));

        for (String ifMatch : List.of("\"3\"", "W/\"3\"", "3")) {
            mockMvc.perform(post("/api/v1/rental-inquiries/" + INQUIRY + "/confirm").principal(customer)
                            .header("If-Match", ifMatch)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quoteVersion\":2,\"agreed\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(4))
                    .andExpect(jsonPath("$.rentalOrderId").value(ORDER.toString()))
                    .andExpect(jsonPath("$.tradeOrderId").value(TRADE.toString()))
                    .andExpect(jsonPath("$.payBefore").value("2026-11-19T02:30:00Z"));
        }
        mockMvc.perform(post("/api/v1/rental-inquiries/" + INQUIRY + "/confirm").principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteVersion\":2,\"agreed\":true}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @EnumSource(Error.class)
    void everyBusinessRejectionHasItsStatusAndCode(Error error) throws Exception {
        when(service.confirm(eq(SUBJECT), eq(INQUIRY), anyInt(), eq(2), eq(true))).thenThrow(new RentalException(error));
        Rejection expected = REJECTIONS.get(error);

        mockMvc.perform(post("/api/v1/rental-inquiries/" + INQUIRY + "/confirm").principal(customer)
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quoteVersion\":2,\"agreed\":true}"))
                .andExpect(status().is(expected.status()))
                .andExpect(jsonPath("$.code").value(expected.code()))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void ordersAreReadListedAndConfirmed() throws Exception {
        when(service.readOrder(SUBJECT, ORDER)).thenReturn(order(RentalOrderStatus.HANDOVER_RECORDED));
        when(service.confirmHandover(SUBJECT, ORDER)).thenReturn(order(RentalOrderStatus.IN_USE));
        when(service.confirmReturn(SUBJECT, ORDER)).thenReturn(order(RentalOrderStatus.ENDED));
        when(service.listOrders(SUBJECT, null, 1, 20)).thenReturn(new RentalOrderPageView(1, 20, 0, false, List.of()));

        mockMvc.perform(get("/api/v1/rental-orders/" + ORDER).principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(18))
                .andExpect(jsonPath("$.status").value("HANDOVER_RECORDED"))
                .andExpect(jsonPath("$.contract.contractNo").value("RC20261119048213"))
                .andExpect(jsonPath("$.contract.endDate").value("2026-11-26"))
                .andExpect(jsonPath("$.contract.lessor.name").value("示例机器人商家"))
                .andExpect(jsonPath("$.contract.lessor.qualificationReviewed").value(true))
                .andExpect(jsonPath("$.payment.amountMinor").value(195000))
                .andExpect(jsonPath("$.handoverRecord.note").value("编号 DOG-0118"))
                .andExpect(jsonPath("$.returnRecord").doesNotExist())
                .andExpect(jsonPath("$.handoverConfirmable").value(true));
        mockMvc.perform(post("/api/v1/rental-orders/" + ORDER + "/handover-confirmation").principal(customer))
                .andExpect(jsonPath("$.status").value("IN_USE"));
        mockMvc.perform(post("/api/v1/rental-orders/" + ORDER + "/return-confirmation").principal(customer))
                .andExpect(jsonPath("$.status").value("ENDED"));
        mockMvc.perform(get("/api/v1/rental-orders").principal(customer))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    // ---- 商家 ----

    @Test
    void merchantsQuoteDeclineAndRunOrdersAsTheirOrganization() throws Exception {
        OffsetDateTime validUntil = OffsetDateTime.parse("2026-11-22T02:00:00Z");
        RentalQuoteDraft draft = new RentalQuoteDraft(38000L, 5, 1, List.of(new RentalFeeDraft("送达与取回", "东营市内", 0L)),
                true, validUntil, "押金不在线收取");
        when(service.quote(MERCHANT, OPERATOR, INQUIRY, draft)).thenReturn(inquiry(RentalInquiryStatus.QUOTED, 1, false));
        when(service.decline(MERCHANT, OPERATOR, INQUIRY, "档期已满")).thenReturn(inquiry(RentalInquiryStatus.DECLINED, 2, false));
        when(service.merchantInquiries(MERCHANT, null, 1, 20)).thenReturn(new RentalInquiryPageView(1, 20, 0, false, List.of()));
        when(service.merchantInquiry(MERCHANT, INQUIRY)).thenReturn(inquiry(RentalInquiryStatus.PENDING, 0, false));
        when(service.merchantOrders(MERCHANT, "IN_USE", 1, 20)).thenReturn(new RentalOrderPageView(1, 20, 0, false, List.of()));
        when(service.merchantOrder(MERCHANT, ORDER)).thenReturn(order(RentalOrderStatus.AWAITING_HANDOVER));
        when(service.recordHandover(MERCHANT, OPERATOR, ORDER, new RentalRecordDraft(null, "编号 DOG-0118")))
                .thenReturn(order(RentalOrderStatus.HANDOVER_RECORDED));
        when(service.recordReturn(MERCHANT, OPERATOR, ORDER, new RentalRecordDraft(validUntil, "配件齐全")))
                .thenReturn(order(RentalOrderStatus.RETURN_RECORDED));
        when(service.cancel(MERCHANT, OPERATOR, ORDER, "档期冲突")).thenReturn(order(RentalOrderStatus.CANCELLED));

        mockMvc.perform(put("/api/v1/provider/rental-inquiries/" + INQUIRY + "/quote").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dailyRentMinor":38000,"days":5,"quantity":1,
                                 "fees":[{"name":"送达与取回","note":"东营市内","amountMinor":0}],
                                 "scheduleAvailable":true,"validUntil":"2026-11-22T10:00:00+08:00","terms":"押金不在线收取"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.status").value("QUOTED"));
        mockMvc.perform(post("/api/v1/provider/rental-inquiries/" + INQUIRY + "/decline").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"档期已满\"}"))
                .andExpect(jsonPath("$.status").value("DECLINED"));
        mockMvc.perform(get("/api/v1/provider/rental-inquiries").principal(operator)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/provider/rental-inquiries/" + INQUIRY).principal(operator))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(get("/api/v1/provider/rental-orders?status=IN_USE").principal(operator)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/provider/rental-orders/" + ORDER).principal(operator))
                .andExpect(jsonPath("$.status").value("AWAITING_HANDOVER"));
        mockMvc.perform(post("/api/v1/provider/rental-orders/" + ORDER + "/handover").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"编号 DOG-0118\"}"))
                .andExpect(jsonPath("$.status").value("HANDOVER_RECORDED"));
        mockMvc.perform(post("/api/v1/provider/rental-orders/" + ORDER + "/return").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"occurredAt\":\"2026-11-22T02:00:00Z\",\"note\":\"配件齐全\"}"))
                .andExpect(jsonPath("$.status").value("RETURN_RECORDED"));
        mockMvc.perform(post("/api/v1/provider/rental-orders/" + ORDER + "/cancel").principal(operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"档期冲突\"}"))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.amountMinor").value(195000));
        verify(service).cancel(MERCHANT, OPERATOR, ORDER, "档期冲突");
    }

    // ---- 数据 ----

    private static Stream<Arguments> customerRoutes() {
        String inquiry = "/api/v1/rental-inquiries/" + INQUIRY;
        String order = "/api/v1/rental-orders/" + ORDER;
        return Stream.of(
                Arguments.of("POST inquiries", post("/api/v1/rental-inquiries").header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"offerId\":\"" + OFFER + "\"}")),
                Arguments.of("GET inquiries", get("/api/v1/rental-inquiries")),
                Arguments.of("GET inquiry", get(inquiry)),
                Arguments.of("POST close", post(inquiry + "/close")),
                Arguments.of("POST confirm", post(inquiry + "/confirm").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quoteVersion\":1,\"agreed\":true}")),
                Arguments.of("GET orders", get("/api/v1/rental-orders")),
                Arguments.of("GET order", get(order)),
                Arguments.of("POST handover-confirmation", post(order + "/handover-confirmation")),
                Arguments.of("POST return-confirmation", post(order + "/return-confirmation")));
    }

    private static Stream<Arguments> merchantRoutes() {
        String inquiry = "/api/v1/provider/rental-inquiries/" + INQUIRY;
        String order = "/api/v1/provider/rental-orders/" + ORDER;
        return Stream.of(
                Arguments.of("GET provider inquiries", get("/api/v1/provider/rental-inquiries")),
                Arguments.of("GET provider inquiry", get(inquiry)),
                Arguments.of("PUT quote", put(inquiry + "/quote").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyRentMinor\":1}")),
                Arguments.of("POST decline", post(inquiry + "/decline").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"满\"}")),
                Arguments.of("GET provider orders", get("/api/v1/provider/rental-orders")),
                Arguments.of("GET provider order", get(order)),
                Arguments.of("POST handover", post(order + "/handover").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"x\"}")),
                Arguments.of("POST return", post(order + "/return").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"x\"}")),
                Arguments.of("POST cancel", post(order + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"x\"}")));
    }

    private static Stream<Arguments> allRoutes() {
        return Stream.concat(customerRoutes(), merchantRoutes());
    }

    private static RentalInquiryView inquiry(RentalInquiryStatus status, int version, boolean confirmable) {
        RentalQuoteView quote = status == RentalInquiryStatus.PENDING ? null : new RentalQuoteView(2, 38000, 5, 1, 190000,
                List.of(new RentalFeeView("送达与取回", "东营市内", 0), new RentalFeeView("保险", null, 5000)), 195000, true,
                NOW.plusDays(3), false, "押金不在线收取", NOW);
        return new RentalInquiryView(INQUIRY, status, status == RentalInquiryStatus.CLOSED ? RentalCloseReason.CUSTOMER : null,
                status == RentalInquiryStatus.DECLINED ? "档期已满" : null,
                new RentalLesseeView(RentalLesseeKind.ORGANIZATION, "东营港务物流", "王女士"),
                new RentalMerchantView(UUID.fromString("c1000000-0000-4000-8000-000000000001"), "示例机器人商家", true),
                new RentalItemView(OFFER, UUID.fromString("c2000000-0000-4000-8000-000000000401"), "巡检机器狗", "标准版",
                        null, 38000L, "DAY"),
                1, LocalDate.parse("2026-11-22"), LocalDate.parse("2026-11-26"), 5, RentalHandoverMode.DELIVERY,
                RentalReturnMode.COLLECT,
                new RentalPlaceView(ADDRESS, "王女士", "13800001234", "山东省", "370500", "东营市", "东营港 示例港路 6 号"),
                "夜间巡逻", NOW, NOW.plusDays(7), quote, confirmable, status == RentalInquiryStatus.QUOTED, null, NOW,
                version);
    }

    private static RentalOrderView order(RentalOrderStatus status) {
        RentalContractView contract = new RentalContractView("RC20261119048213", NOW, 2,
                new RentalLesseeView(RentalLesseeKind.ORGANIZATION, "东营港务物流", "王女士"),
                new RentalMerchantView(UUID.fromString("c1000000-0000-4000-8000-000000000001"), "示例机器人商家", true),
                "巡检机器狗", "标准版", 1, LocalDate.parse("2026-11-22"), LocalDate.parse("2026-11-26"), 5, 38000, 190000,
                List.of(new RentalFeeView("保险", null, 5000)), 195000, RentalHandoverMode.DELIVERY,
                RentalReturnMode.COLLECT,
                new RentalPlaceView(null, "王女士", "13800001234", "山东省", "370500", "东营市", "东营港 示例港路 6 号"),
                "押金不在线收取");
        boolean cancelled = status == RentalOrderStatus.CANCELLED;
        return new RentalOrderView(ORDER, INQUIRY, status, contract,
                new RentalPaymentView(TRADE, 195000, NOW.plusMinutes(30), NOW.plusMinutes(5)),
                status == RentalOrderStatus.AWAITING_HANDOVER ? null
                        : new RentalRecordView(NOW.plusDays(3), "编号 DOG-0118", NOW.plusDays(3), null),
                null, null, null, null, cancelled ? NOW : null, cancelled ? "档期冲突" : null,
                cancelled ? new RentalRefundView(UUID.randomUUID(), 195000, "REQUESTED") : null,
                status == RentalOrderStatus.HANDOVER_RECORDED, false, NOW, NOW, 1);
    }

    private static TestingAuthenticationToken signedIn(UUID subjectId, UUID organizationId, String role) {
        return new TestingAuthenticationToken(
                new CurrentSubject(subjectId, Set.of(role), organizationId, Set.of(), 0), null);
    }

    private record Rejection(int status, String code) {
    }
}
