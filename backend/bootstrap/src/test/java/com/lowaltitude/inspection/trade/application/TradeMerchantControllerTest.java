package com.lowaltitude.inspection.trade.application;

import static org.hamcrest.Matchers.nullValue;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeOrderControllerTest.Rejection;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeDeliveryView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderPageView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeRefundView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 商家操作员的交易接口（E05）：要 PROVIDER_OPERATOR 角色、令牌里有机构；机构和操作员只取自令牌；
 * 发货、核销按 If-Match 的版本，回新的 ETag；退款回 201。没带令牌时的 401 在 AuthenticationSecurityIntegrationTest 里检查。
 */
class TradeMerchantControllerTest {

    private static final UUID OPERATOR = UUID.fromString("c1000000-0000-4000-8000-0000000000a1");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID SOMEONE_ELSE = UUID.fromString("c1000000-0000-4000-8000-000000000002");
    private static final UUID ORDER = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID REFUND = UUID.fromString("e4000000-0000-4000-8000-000000000001");
    private static final Instant REQUESTED = Instant.parse("2026-11-18T09:00:00Z");
    private static final String SHIP_BODY = "{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"SF1234567890\"}";
    private static final String PICKUP_BODY = "{\"pickupCode\":\"123456\"}";
    private static final String REFUND_BODY = "{\"amountMinor\":41000,\"reason\":\"少发一件配件\"}";

    private final TradeOrderService orders = mock(TradeOrderService.class);
    private final TradeRefundService refunds = mock(TradeRefundService.class);
    private final TestingAuthenticationToken operator = signedIn("PROVIDER_OPERATOR", MERCHANT);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TradeMerchantController(orders, refunds))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    // ---- 谁能调 ----

    // 客户、平台运营、服务商管理员、没有机构（或机构、账号不是 UUID）的商家操作员：一律 403，服务一次都不调。
    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void onlyAProviderOperatorOfAnOrganizationGetsIn(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        for (TestingAuthenticationToken who : List.of(
                signedIn("CUSTOMER", MERCHANT),
                signedIn("PLATFORM_OPERATOR", MERCHANT),
                signedIn("PROVIDER_ADMIN", MERCHANT),
                signedIn("PROVIDER_OPERATOR", null),
                jwt(OPERATOR.toString(), List.of("PROVIDER_OPERATOR"), null),
                jwt(OPERATOR.toString(), List.of("PROVIDER_OPERATOR"), " "),
                jwt(OPERATOR.toString(), List.of("PROVIDER_OPERATOR"), "not-a-uuid"),
                jwt("not-a-uuid", List.of("PROVIDER_OPERATOR"), MERCHANT.toString()),
                jwt(OPERATOR.toString(), null, MERCHANT.toString()),
                jwt(OPERATOR.toString(), List.of("CUSTOMER"), MERCHANT.toString()))) {
            mockMvc.perform(request.principal(who))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        }

        verifyNoInteractions(orders, refunds);
    }

    // 过滤器之后如果没有登录主体（正常不会发生），控制器自己也不放行。
    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void noRouteWorksWithoutASignedInSubject(String route, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(orders, refunds);
    }

    // 机构只取自令牌：查询参数、请求体里写别的机构没有用。JWT 带角色和机构也可以。
    @Test
    void theOrganizationAndTheOperatorComeFromTheToken() throws Exception {
        when(orders.listForMerchant(MERCHANT, null, 1, 20)).thenReturn(new TradeOrderPageView(1, 20, 0, false, List.of()));
        when(orders.ship(MERCHANT, OPERATOR, ORDER, 2, "顺丰速运", "SF1234567890"))
                .thenReturn(merchantOrder(TradeOrderStatus.SHIPPED, 3));

        mockMvc.perform(get("/api/v1/provider/trade/orders")
                        .param("organizationId", SOMEONE_ELSE.toString())
                        .principal(jwt(OPERATOR.toString(), List.of("CUSTOMER", "PROVIDER_OPERATOR"),
                                MERCHANT.toString())))
                .andExpect(status().isOk());
        mockMvc.perform(ship("\"2\"").principal(operator)
                        .content("{\"carrierName\":\"顺丰速运\",\"trackingNo\":\"SF1234567890\",\"organizationId\":\""
                                + SOMEONE_ELSE + "\",\"operatorId\":\"" + SOMEONE_ELSE + "\"}"))
                .andExpect(status().isOk());

        verify(orders).listForMerchant(MERCHANT, null, 1, 20);
        verify(orders).ship(MERCHANT, OPERATOR, ORDER, 2, "顺丰速运", "SF1234567890");
    }

    // ---- 订单列表 ----

    @Test
    void theListPassesTheStatusAndPageAndDefaultsToTheFirstPageOfTwenty() throws Exception {
        when(orders.listForMerchant(MERCHANT, "PAID", 2, 10)).thenReturn(new TradeOrderPageView(
                2, 10, 11, false, List.of(merchantOrder(TradeOrderStatus.PAID, 1))));
        when(orders.listForMerchant(MERCHANT, null, 1, 20)).thenReturn(new TradeOrderPageView(1, 20, 0, false, List.of()));

        mockMvc.perform(get("/api/v1/provider/trade/orders")
                        .principal(operator)
                        .param("status", "PAID")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.items[0].orderId").value(ORDER.toString()))
                // 发货要用完整手机号；取货码不给商家。
                .andExpect(jsonPath("$.items[0].delivery.recipientPhone").value("13800138000"))
                .andExpect(jsonPath("$.items[0].delivery.phoneMasked").value("138****8000"))
                .andExpect(jsonPath("$.items[0].delivery.pickupCode").value(nullValue()));
        mockMvc.perform(get("/api/v1/provider/trade/orders").principal(operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        verify(orders).listForMerchant(MERCHANT, "PAID", 2, 10);
        verify(orders).listForMerchant(MERCHANT, null, 1, 20);
    }

    @Test
    void anUnknownStatusOrAnUnreadablePageIsABadRequest() throws Exception {
        when(orders.listForMerchant(MERCHANT, "paid", 1, 20)).thenThrow(new IllegalArgumentException("status is invalid"));

        mockMvc.perform(get("/api/v1/provider/trade/orders").principal(operator).param("status", "paid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/provider/trade/orders").principal(operator).param("size", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    // ---- 发货 ----

    @ParameterizedTest
    @ValueSource(strings = {"\"2\"", "W/\"2\"", "2"})
    void shippingReadsTheVersionFromIfMatchAndAnswersWithTheNewETag(String ifMatch) throws Exception {
        when(orders.ship(MERCHANT, OPERATOR, ORDER, 2, "顺丰速运", "SF1234567890"))
                .thenReturn(merchantOrder(TradeOrderStatus.SHIPPED, 3));

        mockMvc.perform(ship(ifMatch).principal(operator).content(SHIP_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.delivery.length()").value(10))
                .andExpect(jsonPath("$.delivery.recipientPhone").value("13800138000"));

        verify(orders).ship(MERCHANT, OPERATOR, ORDER, 2, "顺丰速运", "SF1234567890");
        verifyNoInteractions(refunds);
    }

    // 物流公司、单号的格式由服务判断：缺了也原样交过去，服务拒绝成 400。
    @Test
    void missingTrackingFieldsReachTheServiceAsNull() throws Exception {
        when(orders.ship(MERCHANT, OPERATOR, ORDER, 0, null, null))
                .thenThrow(new IllegalArgumentException("carrier name is invalid"));

        mockMvc.perform(ship("\"0\"").principal(operator).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verify(orders).ship(MERCHANT, OPERATOR, ORDER, 0, null, null);
    }

    // ---- 自提核销 ----

    @Test
    void aPickupIsCompletedWithTheCodeAtTheVersionOfIfMatch() throws Exception {
        when(orders.completePickup(MERCHANT, OPERATOR, ORDER, 1, "123456"))
                .thenReturn(merchantOrder(TradeOrderStatus.COMPLETED, 2));

        mockMvc.perform(pickup("W/\"1\"").principal(operator).content(PICKUP_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(orders).completePickup(MERCHANT, OPERATOR, ORDER, 1, "123456");
    }

    @Test
    void aWrongOrLockedPickupCodeIsAConflictWithItsOwnCode() throws Exception {
        when(orders.completePickup(MERCHANT, OPERATOR, ORDER, 1, "654321"))
                .thenThrow(new TradeException(Error.PICKUP_CODE_INVALID));
        when(orders.completePickup(MERCHANT, OPERATOR, ORDER, 1, "12345"))
                .thenThrow(new IllegalArgumentException("pickup code must be 6 digits"));

        mockMvc.perform(pickup("\"1\"").principal(operator).content("{\"pickupCode\":\"654321\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRADE_PICKUP_CODE_INVALID"));
        mockMvc.perform(pickup("\"1\"").principal(operator).content("{\"pickupCode\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
    }

    // ---- 退款 ----

    @Test
    void aRefundIsCreatedWithTheKeyAmountAndReason() throws Exception {
        when(refunds.merchantRefund(MERCHANT, OPERATOR, ORDER, KEY, 41_000L, "少发一件配件"))
                .thenReturn(new TradeRefundView(REFUND, 41_000, "少发一件配件", "PROCESSING", REQUESTED, null));

        mockMvc.perform(refund().principal(operator).content(REFUND_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.refundId").value(REFUND.toString()))
                .andExpect(jsonPath("$.amountMinor").value(41_000))
                .andExpect(jsonPath("$.reason").value("少发一件配件"))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.requestedAt").value("2026-11-18T09:00:00Z"))
                .andExpect(jsonPath("$.succeededAt").value(nullValue()));

        verify(refunds).merchantRefund(MERCHANT, OPERATOR, ORDER, KEY, 41_000L, "少发一件配件");
        verifyNoInteractions(orders);
    }

    // 退款不要 If-Match（部分退款不改订单版本）；金额、原因缺了原样交给服务校验。
    @Test
    void aRefundNeedsNoIfMatchAndMissingFieldsReachTheServiceAsNull() throws Exception {
        when(refunds.merchantRefund(MERCHANT, OPERATOR, ORDER, KEY, null, null))
                .thenThrow(new IllegalArgumentException("refund amount must be positive"));

        mockMvc.perform(refund().principal(operator).header("If-Match", "\"7\"").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verify(refunds).merchantRefund(MERCHANT, OPERATOR, ORDER, KEY, null, null);
    }

    @Test
    void refundRefusalsKeepTheirStatusAndCode() throws Exception {
        when(refunds.merchantRefund(MERCHANT, OPERATOR, ORDER, KEY, 41_000L, "少发一件配件"))
                .thenThrow(new TradeException(Error.NOT_FOUND))
                .thenThrow(new TradeException(Error.ORDER_STATE_INVALID))
                .thenThrow(new TradeException(Error.IDEMPOTENCY_CONFLICT))
                .thenThrow(new TradeException(Error.REFUND_EXCEEDS_PAID))
                .thenThrow(new TradeException(Error.REFUND_LIMIT_REACHED))
                .thenThrow(new TradeException(Error.CHANNEL_UNAVAILABLE));

        for (Rejection expected : List.of(
                new Rejection(404, "COMMON_NOT_FOUND"),
                new Rejection(409, "TRADE_ORDER_STATE_INVALID"),
                new Rejection(409, "TRADE_IDEMPOTENCY_KEY_REUSED"),
                new Rejection(409, "TRADE_REFUND_EXCEEDS_PAID"),
                new Rejection(409, "TRADE_REFUND_LIMIT_REACHED"),
                new Rejection(503, "TRADE_CHANNEL_UNAVAILABLE"))) {
            mockMvc.perform(refund().principal(operator).content(REFUND_BODY))
                    .andExpect(status().is(expected.status()))
                    .andExpect(jsonPath("$.code").value(expected.code()));
        }
    }

    // ---- 请求格式 ----

    // 发货、核销要幂等键和 If-Match；退款要幂等键；都要请求体。格式不对的一律 400，服务一次都不调。
    @Test
    void unreadableRequestsAreBadRequests() throws Exception {
        String order = "/api/v1/provider/trade/orders/" + ORDER;
        for (MockHttpServletRequestBuilder request : List.of(
                post(order + "/shipment").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(SHIP_BODY),
                post(order + "/shipment").header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(SHIP_BODY),
                ship("abc").content(SHIP_BODY),
                ship("\"-1\"").content(SHIP_BODY),
                ship("\"0\""),
                ship("\"0\"").content("{\"carrierName\":"),
                post(order + "/pickup").header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(PICKUP_BODY),
                pickup("\"0\""),
                post(order + "/refunds").contentType(MediaType.APPLICATION_JSON).content(REFUND_BODY),
                post(order + "/refunds").header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON).content(REFUND_BODY),
                refund(),
                refund().content("{\"amountMinor\":\"lots\",\"reason\":\"少发一件\"}"),
                post("/api/v1/provider/trade/orders/not-a-uuid/refunds").header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(REFUND_BODY))) {
            mockMvc.perform(request.principal(operator))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        }

        verifyNoInteractions(orders, refunds);
    }

    // 发货、核销的业务拒绝：别的机构的单当作不存在、状态不对、版本不对。
    @Test
    void shipmentAndPickupRefusalsKeepTheirStatusAndCode() throws Exception {
        when(orders.ship(MERCHANT, OPERATOR, ORDER, 0, "顺丰速运", "SF1234567890"))
                .thenThrow(new TradeException(Error.NOT_FOUND))
                .thenThrow(new TradeException(Error.ORDER_STATE_INVALID))
                .thenThrow(new TradeException(Error.VERSION_CONFLICT));
        when(orders.completePickup(MERCHANT, OPERATOR, ORDER, 0, "123456"))
                .thenThrow(new TradeException(Error.NOT_FOUND))
                .thenThrow(new TradeException(Error.ORDER_STATE_INVALID))
                .thenThrow(new TradeException(Error.VERSION_CONFLICT));

        for (Rejection expected : List.of(
                new Rejection(404, "COMMON_NOT_FOUND"),
                new Rejection(409, "TRADE_ORDER_STATE_INVALID"),
                new Rejection(412, "TRADE_VERSION_CONFLICT"))) {
            mockMvc.perform(ship("\"0\"").principal(operator).content(SHIP_BODY))
                    .andExpect(status().is(expected.status()))
                    .andExpect(jsonPath("$.code").value(expected.code()));
            mockMvc.perform(pickup("\"0\"").principal(operator).content(PICKUP_BODY))
                    .andExpect(status().is(expected.status()))
                    .andExpect(jsonPath("$.code").value(expected.code()));
        }
    }

    // ---- 辅助 ----

    private static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of("GET orders", get("/api/v1/provider/trade/orders")),
                Arguments.of("POST shipment", ship("\"0\"").content(SHIP_BODY)),
                Arguments.of("POST pickup", pickup("\"0\"").content(PICKUP_BODY)),
                Arguments.of("POST refunds", refund().content(REFUND_BODY)));
    }

    private static MockHttpServletRequestBuilder ship(String ifMatch) {
        return post("/api/v1/provider/trade/orders/{orderId}/shipment", ORDER)
                .header("Idempotency-Key", KEY.toString())
                .header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static MockHttpServletRequestBuilder pickup(String ifMatch) {
        return post("/api/v1/provider/trade/orders/{orderId}/pickup", ORDER)
                .header("Idempotency-Key", KEY.toString())
                .header("If-Match", ifMatch)
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static MockHttpServletRequestBuilder refund() {
        return post("/api/v1/provider/trade/orders/{orderId}/refunds", ORDER)
                .header("Idempotency-Key", KEY.toString())
                .contentType(MediaType.APPLICATION_JSON);
    }

    /** 商家看到的订单：和买家的一样，多了完整手机号。 */
    private static TradeOrderView merchantOrder(TradeOrderStatus status, int version) {
        TradeOrderView base = TradeOrderControllerTest.order(status, version);
        TradeDeliveryView delivery = base.delivery();
        return new TradeOrderView(base.orderId(), base.orderNo(), base.source(), base.sourceRef(), base.status(), base.merchant(),
                base.lines(),
                new TradeDeliveryView(delivery.mode(), delivery.recipientName(), delivery.phoneMasked(), "13800138000",
                        delivery.addressLine(), delivery.pickupPoint(), null, delivery.carrierName(),
                        delivery.trackingNo(), delivery.shippedAt()),
                base.amounts(), base.buyerNote(), base.createdAt(), base.payBefore(), base.paidAt(),
                base.completedAt(), base.closedAt(), base.closeReason(), base.refunds(), base.cancellable(),
                base.version());
    }

    private static TestingAuthenticationToken signedIn(String role, UUID organizationId) {
        return new TestingAuthenticationToken(
                new CurrentSubject(OPERATOR, Set.of(role), organizationId, Set.of(), 0), null);
    }

    private static TestingAuthenticationToken jwt(String subjectId, List<String> roles, String organizationId) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("subjectId", subjectId);
        if (roles != null) {
            builder.claim("roles", roles);
        }
        if (organizationId != null) {
            builder.claim("organizationId", organizationId);
        }
        return new TestingAuthenticationToken(builder.build(), null);
    }
}
