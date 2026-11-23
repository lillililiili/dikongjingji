package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.lowaltitude.inspection.account.application.AddressNotFoundException;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.mall.application.MallCartException;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeOrderService.SubmitCommand;
import com.lowaltitude.inspection.trade.application.TradeViews.PaymentLaunchView;
import com.lowaltitude.inspection.trade.application.TradeViews.WechatAppCallView;
import com.lowaltitude.inspection.trade.application.TradeViews.SubmitResult;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeAmountsView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeDeliveryView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeMediaView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeMerchantView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderLineView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderPageView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 买家订单接口：从令牌里取客户账号、把请求转成服务的参数、把结果转成契约里的状态码和 ETag、把业务拒绝转成错误码。
 * 没带令牌时的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里检查；这里的 standaloneSetup 不带过滤器。
 */
class TradeOrderControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOMEONE_ELSE = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID ORDER = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID CART_A = UUID.fromString("c5000000-0000-4000-8000-000000000001");
    private static final UUID CART_B = UUID.fromString("c5000000-0000-4000-8000-000000000002");
    private static final UUID OFFER = UUID.fromString("c4000000-0000-4000-8000-000000000101");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000101");
    private static final UUID ADDRESS = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private static final Instant CREATED = Instant.parse("2026-11-18T08:00:00Z");
    private static final String BODY = """
            {"cityCode":"370500","cartItemIds":["c5000000-0000-4000-8000-000000000001",
             "c5000000-0000-4000-8000-000000000002"],"deliveryMode":"EXPRESS",
             "addressId":"d1000000-0000-4000-8000-000000000001","buyerNote":"请周末送",
             "expectedPayableMinor":141000,"agreementAccepted":true}
            """;
    private static final String LAUNCH_BODY = "{\"channel\":\"WECHAT_APP\"}";
    private static final UUID PAYMENT = UUID.fromString("e3000000-0000-4000-8000-000000000001");
    private static final PaymentLaunchView LAUNCHED = new PaymentLaunchView(
            PAYMENT, "WECHAT_APP", CREATED.plusSeconds(1800),
            new WechatAppCallView("wx_app", "1900000001", "wx_prepay_1", "Sign=WXPay", "nonce", "1763452800",
                    "c2lnbmF0dXJl"));
    private static final SubmitCommand COMMAND = new SubmitCommand(
            "370500", List.of(CART_A, CART_B), null, null, "EXPRESS", ADDRESS, "请周末送", 141_000L, true);

    /** 每一种业务拒绝对外的状态码和错误码（GlobalExceptionHandler 与契约 openapi-trade.yaml 0.1.0）。 */
    static final Map<Error, Rejection> REJECTIONS = new EnumMap<>(Map.ofEntries(
            Map.entry(Error.NOT_FOUND, new Rejection(404, "COMMON_NOT_FOUND")),
            Map.entry(Error.FORBIDDEN, new Rejection(403, "COMMON_FORBIDDEN")),
            Map.entry(Error.IDEMPOTENCY_CONFLICT, new Rejection(409, "TRADE_IDEMPOTENCY_KEY_REUSED")),
            Map.entry(Error.AMOUNT_CHANGED, new Rejection(409, "TRADE_AMOUNT_CHANGED")),
            Map.entry(Error.ADDRESS_REQUIRED, new Rejection(409, "TRADE_ADDRESS_REQUIRED")),
            Map.entry(Error.DELIVERY_UNAVAILABLE, new Rejection(409, "TRADE_DELIVERY_UNAVAILABLE")),
            Map.entry(Error.MERCHANT_PAYMENT_UNAVAILABLE, new Rejection(409, "TRADE_MERCHANT_PAYMENT_UNAVAILABLE")),
            Map.entry(Error.ORDER_STATE_INVALID, new Rejection(409, "TRADE_ORDER_STATE_INVALID")),
            Map.entry(Error.VERSION_CONFLICT, new Rejection(412, "TRADE_VERSION_CONFLICT")),
            Map.entry(Error.ORDER_EXPIRED, new Rejection(409, "TRADE_ORDER_EXPIRED")),
            Map.entry(Error.CHANNEL_UNAVAILABLE, new Rejection(503, "TRADE_CHANNEL_UNAVAILABLE")),
            Map.entry(Error.PICKUP_CODE_INVALID, new Rejection(409, "TRADE_PICKUP_CODE_INVALID")),
            Map.entry(Error.REFUND_EXCEEDS_PAID, new Rejection(409, "TRADE_REFUND_EXCEEDS_PAID")),
            Map.entry(Error.REFUND_LIMIT_REACHED, new Rejection(409, "TRADE_REFUND_LIMIT_REACHED")),
            // E06：读对账结果时那天的账单还没全部导入。
            Map.entry(Error.RECONCILIATION_NOT_READY, new Rejection(409, "TRADE_RECONCILIATION_NOT_READY")),
            Map.entry(Error.QUOTE_ALREADY_ORDERED, new Rejection(409, "TRADE_INQUIRY_QUOTE_ORDERED")),
            // 0.6.0（S5-8）：注销冷静期内不能下单和付款。
            Map.entry(Error.ACCOUNT_CLOSING, new Rejection(409, "TRADE_ACCOUNT_CLOSING")),
            // 只在交易模块内部流转，由支付服务、退款服务接住；万一漏出来，对买家就是“订单状态不允许”。
            Map.entry(Error.PAYMENT_IN_PROGRESS, new Rejection(409, "TRADE_ORDER_STATE_INVALID")),
            Map.entry(Error.REFUND_REQUIRED, new Rejection(409, "TRADE_ORDER_STATE_INVALID"))));

    private final TradeOrderService service = mock(TradeOrderService.class);
    private final TradePaymentService payments = mock(TradePaymentService.class);
    private final TestingAuthenticationToken customer = signedIn("CUSTOMER");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // 与应用一致的 JSON 配置：时间写成 ISO 文本。
        mockMvc = MockMvcBuilders.standaloneSetup(new TradeOrderController(service, payments))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    // ---- 谁能调 ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void everyRouteRefusesAnAccountThatIsNotACustomer(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        for (String role : List.of("PROVIDER_OPERATOR", "PLATFORM_OPERATOR")) {
            mockMvc.perform(request.principal(signedIn(role)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }

        verifyNoInteractions(service, payments);
    }

    // 过滤器之后如果没有登录主体（正常不会发生），控制器自己也不放行。
    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void noRouteWorksWithoutASignedInSubject(String route, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(service, payments);
    }

    @Test
    void theBuyerComesFromTheTokenAndAJwtNeedsTheCustomerRole() throws Exception {
        when(service.list(SUBJECT, null, 1, 20)).thenReturn(new TradeOrderPageView(1, 20, 0, false, List.of()));
        when(service.submit(SUBJECT, KEY, COMMAND)).thenReturn(new SubmitResult(order(TradeOrderStatus.PENDING_PAYMENT, 0), true));

        mockMvc.perform(get("/api/v1/trade/orders")
                        .param("buyerId", SOMEONE_ELSE.toString())
                        .principal(jwt(SUBJECT.toString(), List.of("CUSTOMER"))))
                .andExpect(status().isOk());
        mockMvc.perform(submit().principal(customer)
                        .content(BODY.replace("}", ",\"buyerId\":\"" + SOMEONE_ELSE + "\"}")))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/trade/orders").principal(jwt(SUBJECT.toString(), null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/trade/orders").principal(jwt(SUBJECT.toString(), List.of("PROVIDER_OPERATOR"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/trade/orders").principal(jwt("not-a-uuid", List.of("CUSTOMER"))))
                .andExpect(status().isForbidden());

        verify(service).list(SUBJECT, null, 1, 20);
        verify(service).submit(SUBJECT, KEY, COMMAND);
    }

    // ---- 提交 ----

    @Test
    void aNewOrderIsCreatedWithItsVersionAsTheETag() throws Exception {
        when(service.submit(SUBJECT, KEY, COMMAND))
                .thenReturn(new SubmitResult(order(TradeOrderStatus.PENDING_PAYMENT, 0), true));

        mockMvc.perform(submit().principal(customer).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                // 0.6.0：多了付款主体 payer。
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$.payer.type").value("PERSONAL"))
                .andExpect(jsonPath("$.orderId").value(ORDER.toString()))
                .andExpect(jsonPath("$.orderNo").value("MO2026111800000042"))
                .andExpect(jsonPath("$.source").value("MALL"))
                // 0.2.0：商城订单没有业务单。
                .andExpect(jsonPath("$.sourceRef").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.merchant.organizationId").value(MERCHANT.toString()))
                .andExpect(jsonPath("$.merchant.kind").value("LOCAL_STORE"))
                .andExpect(jsonPath("$.lines[0].length()").value(9))
                .andExpect(jsonPath("$.lines[0].coverImage.url").value("https://img.example.com/a.png"))
                .andExpect(jsonPath("$.delivery.length()").value(10))
                .andExpect(jsonPath("$.delivery.phoneMasked").value("138****8000"))
                // 完整手机号只在商家接口给。
                .andExpect(jsonPath("$.delivery.recipientPhone").value(nullValue()))
                .andExpect(jsonPath("$.delivery.pickupCode").value(nullValue()))
                .andExpect(jsonPath("$.amounts.payableMinor").value(141_000))
                .andExpect(jsonPath("$.amounts.currency").value("CNY"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-18T08:00:00Z"))
                .andExpect(jsonPath("$.payBefore").value("2026-11-18T08:30:00Z"))
                .andExpect(jsonPath("$.paidAt").value(nullValue()))
                // 待付款的单没有关闭原因，但字段照样给出（契约里是必填、可为 null）。
                .andExpect(jsonPath("$.closeReason").value(nullValue()))
                .andExpect(jsonPath("$.refunds").isEmpty())
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.version").value(0));

        verify(service).submit(SUBJECT, KEY, COMMAND);
    }

    @Test
    void aReplayedSubmissionAnswersOkWithTheFirstOrder() throws Exception {
        when(service.submit(SUBJECT, KEY, COMMAND))
                .thenReturn(new SubmitResult(order(TradeOrderStatus.CLOSED, 1), false));

        mockMvc.perform(submit().principal(customer).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void aBuyNowBodyAndMissingOptionalFieldsReachTheServiceAsNull() throws Exception {
        SubmitCommand buyNow = new SubmitCommand("370500", null, OFFER, 2, "PICKUP", null, null, 100_000L, null);
        when(service.submit(SUBJECT, KEY, buyNow))
                .thenReturn(new SubmitResult(order(TradeOrderStatus.PENDING_PAYMENT, 0), true));

        mockMvc.perform(submit().principal(customer).content("""
                        {"cityCode":"370500","offerId":"c4000000-0000-4000-8000-000000000101","quantity":2,
                         "deliveryMode":"PICKUP","expectedPayableMinor":100000}
                        """))
                .andExpect(status().isCreated());

        verify(service).submit(SUBJECT, KEY, buyNow);
    }

    @Test
    void aSubmissionWithoutAUsableIdempotencyKeyOrBodyIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/trade/orders")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/trade/orders")
                        .principal(customer)
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(submit().principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(submit().principal(customer).content("{\"cityCode\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(submit().principal(customer).content(BODY.replace("141000", "\"lots\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(service, payments);
    }

    @Test
    void anInvalidSubmissionTheServiceRefusesIsABadRequest() throws Exception {
        when(service.submit(SUBJECT, KEY, COMMAND)).thenThrow(new IllegalArgumentException("agreement is required"));

        mockMvc.perform(submit().principal(customer).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    // 报价由商城和地址簿给出：它们的业务拒绝原样转成各自的错误码。
    @Test
    void refusalsFromTheMallAndTheAddressBookKeepTheirCodes() throws Exception {
        when(service.submit(SUBJECT, KEY, COMMAND))
                .thenThrow(new MallCartException(MallCartException.Error.ITEM_UNAVAILABLE))
                .thenThrow(new MallCartException(MallCartException.Error.MULTIPLE_MERCHANTS))
                .thenThrow(new MallCartException(MallCartException.Error.NOT_PURCHASABLE))
                .thenThrow(new AddressNotFoundException());

        for (String code : List.of(
                "MALL_CHECKOUT_ITEM_UNAVAILABLE", "MALL_CHECKOUT_MULTIPLE_MERCHANTS", "MALL_CART_NOT_PURCHASABLE")) {
            mockMvc.perform(submit().principal(customer).content(BODY))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value(code));
        }
        mockMvc.perform(submit().principal(customer).content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    // ---- 列表与详情 ----

    @Test
    void theListPassesTheTabAndPageAndDefaultsToTheFirstPageOfTwenty() throws Exception {
        when(service.list(SUBJECT, "TO_PAY", 2, 10)).thenReturn(new TradeOrderPageView(
                2, 10, 11, false, List.of(order(TradeOrderStatus.PENDING_PAYMENT, 0))));
        when(service.list(SUBJECT, null, 1, 20)).thenReturn(new TradeOrderPageView(1, 20, 0, false, List.of()));

        mockMvc.perform(get("/api/v1/trade/orders")
                        .principal(customer)
                        .param("tab", "TO_PAY")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.items[0].orderId").value(ORDER.toString()));
        mockMvc.perform(get("/api/v1/trade/orders").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        verify(service).list(SUBJECT, "TO_PAY", 2, 10);
        verify(service).list(SUBJECT, null, 1, 20);
    }

    @Test
    void anUnknownTabOrAnUnreadablePageIsABadRequest() throws Exception {
        when(service.list(SUBJECT, "CLOSED", 1, 20)).thenThrow(new IllegalArgumentException("order tab is invalid"));

        mockMvc.perform(get("/api/v1/trade/orders").principal(customer).param("tab", "CLOSED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/trade/orders").principal(customer).param("page", "first"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    @Test
    void anOrderIsReadWithItsVersionAsTheETag() throws Exception {
        when(service.read(SUBJECT, ORDER)).thenReturn(order(TradeOrderStatus.PAID, 3));

        mockMvc.perform(get("/api/v1/trade/orders/{orderId}", ORDER).principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void anOrderThatIsNotTheBuyersIsNotFoundAndAMalformedIdIsABadRequest() throws Exception {
        when(service.read(SUBJECT, ORDER)).thenThrow(new TradeException(Error.NOT_FOUND));

        mockMvc.perform(get("/api/v1/trade/orders/{orderId}", ORDER)
                        .principal(customer)
                        .header("X-Request-Id", "trace-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").value("trace-1"));
        mockMvc.perform(get("/api/v1/trade/orders/not-a-uuid").principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    // ---- 取消 ----

    @ParameterizedTest
    @ValueSource(strings = {"\"3\"", "W/\"3\"", "3", " \"3\" "})
    void cancellingReadsTheVersionFromIfMatch(String ifMatch) throws Exception {
        when(payments.cancel(SUBJECT, ORDER, 3, KEY)).thenReturn(order(TradeOrderStatus.CLOSED, 4));

        mockMvc.perform(cancel(ifMatch).principal(customer))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closeReason").value("BUYER_CANCEL"))
                .andExpect(jsonPath("$.cancellable").value(false));

        // 取消交给支付服务：发起过支付的单要先向渠道查单。
        verify(payments).cancel(SUBJECT, ORDER, 3, KEY);
        verifyNoInteractions(service);
    }

    @Test
    void aBodyOnCancelIsIgnored() throws Exception {
        when(payments.cancel(SUBJECT, ORDER, 0, KEY)).thenReturn(order(TradeOrderStatus.CLOSED, 1));

        mockMvc.perform(cancel("\"0\"")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不想要了\"}"))
                .andExpect(status().isOk());

        verify(payments).cancel(SUBJECT, ORDER, 0, KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "*", "abc", "\"-1\"", "W/", "\"1.5\"", "99999999999"})
    void anUnreadableIfMatchIsABadRequest(String ifMatch) throws Exception {
        mockMvc.perform(cancel(ifMatch).principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(service, payments);
    }

    @Test
    void cancellingWithoutIfMatchOrIdempotencyKeyIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/trade/orders/{orderId}/cancel", ORDER)
                        .principal(customer)
                        .header("Idempotency-Key", KEY.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/trade/orders/{orderId}/cancel", ORDER)
                        .principal(customer)
                        .header("If-Match", "\"0\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(service, payments);
    }

    @Test
    void theVersionParserAcceptsOnlyANonNegativeInteger() {
        assertThat(TradeOrderController.version("\"0\"")).isZero();
        assertThat(TradeOrderController.version("W/\"12\"")).isEqualTo(12);
        assertThat(TradeOrderController.version("7")).isEqualTo(7);
        for (String invalid : Arrays.asList(null, "", "\"-1\"", "x", "W/\"\"", "2147483648")) {
            assertThatThrownBy(() -> TradeOrderController.version(invalid))
                    .as(String.valueOf(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // 已付款未发货的单取消即退款：幂等键原样交给支付服务（再转给退款服务），同一个键重放不会退两次。
    @Test
    void cancellingPassesTheIdempotencyKeyForTheRefund() throws Exception {
        UUID otherKey = UUID.fromString("a0000000-0000-4000-8000-000000000009");
        when(payments.cancel(SUBJECT, ORDER, 1, otherKey)).thenReturn(order(TradeOrderStatus.REFUNDING, 2));

        mockMvc.perform(post("/api/v1/trade/orders/{orderId}/cancel", ORDER)
                        .principal(customer)
                        .header("Idempotency-Key", otherKey.toString())
                        .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("REFUNDING"));

        verify(payments).cancel(SUBJECT, ORDER, 1, otherKey);
        verifyNoInteractions(service);
    }

    @Test
    void aCancelWithAnUnreadableIdempotencyKeyIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/trade/orders/{orderId}/cancel", ORDER)
                        .principal(customer)
                        .header("Idempotency-Key", "not-a-uuid")
                        .header("If-Match", "\"0\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(service, payments);
    }

    // ---- 确认收货（E05） ----

    @ParameterizedTest
    @ValueSource(strings = {"\"3\"", "W/\"3\"", "3"})
    void receivingReadsTheVersionFromIfMatchAndAnswersWithTheNewETag(String ifMatch) throws Exception {
        when(service.confirmReceipt(SUBJECT, ORDER, 3)).thenReturn(order(TradeOrderStatus.COMPLETED, 4));

        mockMvc.perform(receipt(ifMatch).principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyerId\":\"" + SOMEONE_ELSE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.version").value(4));

        // 确认收货只动订单，不经支付服务。
        verify(service).confirmReceipt(SUBJECT, ORDER, 3);
        verifyNoInteractions(payments);
    }

    @Test
    void receivingWithoutIfMatchOrIdempotencyKeyIsABadRequest() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                post("/api/v1/trade/orders/{orderId}/receipt", ORDER).header("Idempotency-Key", KEY.toString()),
                post("/api/v1/trade/orders/{orderId}/receipt", ORDER).header("If-Match", "\"0\""),
                receipt("abc"),
                post("/api/v1/trade/orders/not-a-uuid/receipt")
                        .header("Idempotency-Key", KEY.toString())
                        .header("If-Match", "\"0\""))) {
            mockMvc.perform(request.principal(customer))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }

        verifyNoInteractions(service, payments);
    }

    @Test
    void receiptRefusalsKeepTheirStatusAndCode() throws Exception {
        when(service.confirmReceipt(SUBJECT, ORDER, 0))
                .thenThrow(new TradeException(Error.NOT_FOUND))
                .thenThrow(new TradeException(Error.ORDER_STATE_INVALID))
                .thenThrow(new TradeException(Error.VERSION_CONFLICT));

        for (Rejection expected : List.of(
                new Rejection(404, "COMMON_NOT_FOUND"),
                new Rejection(409, "TRADE_ORDER_STATE_INVALID"),
                new Rejection(412, "TRADE_VERSION_CONFLICT"))) {
            mockMvc.perform(receipt("\"0\"").principal(customer))
                    .andExpect(status().is(expected.status()))
                    .andExpect(jsonPath("$.code").value(expected.code()));
        }
    }

    // ---- 发起支付 ----

    @Test
    void launchingAPaymentReturnsTheAppCallForTheBuyerOfTheToken() throws Exception {
        when(payments.launch(SUBJECT, ORDER, KEY, "WECHAT_APP")).thenReturn(LAUNCHED);

        mockMvc.perform(launch().principal(customer)
                        .content("{\"channel\":\"WECHAT_APP\",\"buyerId\":\"" + SOMEONE_ELSE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.paymentId").value(PAYMENT.toString()))
                .andExpect(jsonPath("$.channel").value("WECHAT_APP"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-18T08:30:00Z"))
                .andExpect(jsonPath("$.appCall.length()").value(7))
                .andExpect(jsonPath("$.appCall.appId").value("wx_app"))
                .andExpect(jsonPath("$.appCall.partnerId").value("1900000001"))
                .andExpect(jsonPath("$.appCall.prepayId").value("wx_prepay_1"))
                .andExpect(jsonPath("$.appCall.packageValue").value("Sign=WXPay"))
                .andExpect(jsonPath("$.appCall.nonceStr").value("nonce"))
                .andExpect(jsonPath("$.appCall.timeStamp").value("1763452800"))
                .andExpect(jsonPath("$.appCall.sign").value("c2lnbmF0dXJl"));

        verify(payments).launch(SUBJECT, ORDER, KEY, "WECHAT_APP");
        verifyNoInteractions(service);
    }

    // 渠道名原样交给服务校验：不认识的、没给的都由服务拒绝成 400。
    @Test
    void theChannelIsPassedAsSentAndAnUnknownOneIsABadRequest() throws Exception {
        when(payments.launch(SUBJECT, ORDER, KEY, "WECHAT_JSAPI"))
                .thenThrow(new IllegalArgumentException("channel is not supported"));
        when(payments.launch(SUBJECT, ORDER, KEY, null))
                .thenThrow(new IllegalArgumentException("channel is not supported"));

        for (String body : List.of("{\"channel\":\"WECHAT_JSAPI\"}", "{}", "{\"channel\":null}")) {
            mockMvc.perform(launch().principal(customer).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }

        verify(payments).launch(SUBJECT, ORDER, KEY, "WECHAT_JSAPI");
        verify(payments, org.mockito.Mockito.times(2)).launch(SUBJECT, ORDER, KEY, null);
    }

    @Test
    void aLaunchWithoutAUsableKeyBodyOrOrderIdIsABadRequest() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                post("/api/v1/trade/orders/{orderId}/payments", ORDER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LAUNCH_BODY),
                post("/api/v1/trade/orders/{orderId}/payments", ORDER)
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LAUNCH_BODY),
                launch(),
                launch().content("null"),
                launch().content("{\"channel\":"),
                launch().content("{\"channel\":[\"WECHAT_APP\"]}"),
                post("/api/v1/trade/orders/not-a-uuid/payments")
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LAUNCH_BODY));
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.principal(customer))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }

        verifyNoInteractions(service, payments);
    }

    @Test
    void launchRefusalsKeepTheirStatusAndCode() throws Exception {
        when(payments.launch(SUBJECT, ORDER, KEY, "WECHAT_APP"))
                .thenThrow(new TradeException(Error.NOT_FOUND))
                .thenThrow(new TradeException(Error.ORDER_STATE_INVALID))
                .thenThrow(new TradeException(Error.ORDER_EXPIRED))
                .thenThrow(new TradeException(Error.CHANNEL_UNAVAILABLE));

        for (Rejection expected : List.of(
                new Rejection(404, "COMMON_NOT_FOUND"),
                new Rejection(409, "TRADE_ORDER_STATE_INVALID"),
                new Rejection(409, "TRADE_ORDER_EXPIRED"),
                new Rejection(503, "TRADE_CHANNEL_UNAVAILABLE"))) {
            mockMvc.perform(launch().principal(customer).content(LAUNCH_BODY))
                    .andExpect(status().is(expected.status()))
                    .andExpect(jsonPath("$.code").value(expected.code()));
        }
    }

    // ---- App 回来后查一次单 ----

    @Test
    void aPaymentCheckReturnsTheOrderWithItsVersionAsTheETagAndIgnoresWhatTheAppSays() throws Exception {
        when(payments.check(SUBJECT, ORDER)).thenReturn(order(TradeOrderStatus.PAID, 1));

        mockMvc.perform(post("/api/v1/trade/orders/{orderId}/payment-check", ORDER)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\",\"paymentId\":\"" + PAYMENT + "\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.version").value(1));
        // 不要幂等键，也不要请求体。
        mockMvc.perform(post("/api/v1/trade/orders/{orderId}/payment-check", ORDER).principal(customer))
                .andExpect(status().isOk());

        verify(payments, org.mockito.Mockito.times(2)).check(SUBJECT, ORDER);
        verifyNoInteractions(service);
    }

    @Test
    void paymentCheckRefusalsKeepTheirStatusAndCode() throws Exception {
        when(payments.check(SUBJECT, ORDER))
                .thenThrow(new TradeException(Error.NOT_FOUND))
                .thenThrow(new TradeException(Error.CHANNEL_UNAVAILABLE));

        mockMvc.perform(post("/api/v1/trade/orders/{orderId}/payment-check", ORDER).principal(customer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/trade/orders/{orderId}/payment-check", ORDER).principal(customer))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TRADE_CHANNEL_UNAVAILABLE"));
        mockMvc.perform(post("/api/v1/trade/orders/not-a-uuid/payment-check").principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    // ---- 每一种业务拒绝 ----

    @ParameterizedTest
    @EnumSource(Error.class)
    void everyTradeRefusalHasItsStatusAndCode(Error error) throws Exception {
        Rejection expected = REJECTIONS.get(error);
        when(payments.cancel(any(), any(), anyInt(), any())).thenThrow(new TradeException(error));

        mockMvc.perform(cancel("\"0\"").principal(customer).header("X-Request-Id", "trace-2"))
                .andExpect(status().is(expected.status()))
                .andExpect(jsonPath("$.code").value(expected.code()))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value("trace-2"))
                .andExpect(jsonPath("$.details").value(nullValue()));
    }

    @Test
    void everyTradeErrorHasAnExpectedRejection() {
        assertThat(REJECTIONS.keySet()).containsExactlyInAnyOrder(Error.values());
    }

    // ---- 辅助 ----

    private static Stream<Arguments> routes() {
        String order = "/api/v1/trade/orders/" + ORDER;
        return Stream.of(
                Arguments.of("POST orders", submit().content(BODY)),
                Arguments.of("GET orders", get("/api/v1/trade/orders")),
                Arguments.of("GET order", get(order)),
                Arguments.of("POST cancel", cancel("\"0\"")),
                Arguments.of("POST receipt", receipt("\"0\"")),
                Arguments.of("POST payments", launch().content(LAUNCH_BODY)),
                Arguments.of("POST payment-check", post(order + "/payment-check")));
    }

    private static MockHttpServletRequestBuilder launch() {
        return post("/api/v1/trade/orders/{orderId}/payments", ORDER)
                .header("Idempotency-Key", KEY.toString())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static MockHttpServletRequestBuilder submit() {
        return post("/api/v1/trade/orders")
                .header("Idempotency-Key", KEY.toString())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static MockHttpServletRequestBuilder receipt(String ifMatch) {
        return post("/api/v1/trade/orders/{orderId}/receipt", ORDER)
                .header("Idempotency-Key", KEY.toString())
                .header("If-Match", ifMatch);
    }

    private static MockHttpServletRequestBuilder cancel(String ifMatch) {
        return post("/api/v1/trade/orders/{orderId}/cancel", ORDER)
                .header("Idempotency-Key", KEY.toString())
                .header("If-Match", ifMatch);
    }

    static TradeOrderView order(TradeOrderStatus status, int version) {
        boolean pending = status == TradeOrderStatus.PENDING_PAYMENT;
        return new TradeOrderView(
                ORDER,
                "MO2026111800000042",
                "MALL",
                null,
                status,
                new TradeMerchantView(MERCHANT, "示例无人机专卖店", "LOCAL_STORE"),
                List.of(new TradeOrderLineView(
                        UUID.fromString("e2000000-0000-4000-8000-000000000001"),
                        PRODUCT,
                        OFFER,
                        "示例无人机",
                        "标准版",
                        new TradeMediaView("https://img.example.com/a.png", "示例无人机"),
                        100_000,
                        1,
                        100_000)),
                new TradeDeliveryView("EXPRESS", "王女士", "138****8000", null, "山东省东营市 东营区示例路 18 号",
                        null, null, null, null, null),
                new TradeAmountsView(140_000, 1_000, 141_000, pending ? 0 : 141_000, 0, "CNY"),
                "请周末送",
                CREATED,
                pending ? CREATED.plusSeconds(1800) : null,
                pending || status == TradeOrderStatus.CLOSED ? null : CREATED.plusSeconds(600),
                null,
                status == TradeOrderStatus.CLOSED ? CREATED.plusSeconds(1800) : null,
                status == TradeOrderStatus.CLOSED ? "BUYER_CANCEL" : null,
                List.of(),
                pending,
                version);
    }

    private static TestingAuthenticationToken signedIn(String role) {
        return new TestingAuthenticationToken(new CurrentSubject(SUBJECT, Set.of(role), null, Set.of(), 0), null);
    }

    private static TestingAuthenticationToken jwt(String subjectId, List<String> roles) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("subjectId", subjectId);
        if (roles != null) {
            builder.claim("roles", roles);
        }
        return new TestingAuthenticationToken(builder.build(), null);
    }

    record Rejection(int status, String code) {
    }
}
