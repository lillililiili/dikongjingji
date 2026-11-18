package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.lowaltitude.inspection.account.application.AccountAddressService;
import com.lowaltitude.inspection.account.application.AddressNotFoundException;
import com.lowaltitude.inspection.account.application.AddressRegionView;
import com.lowaltitude.inspection.account.application.AddressView;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.mall.application.MallCartController.CheckoutBlocker;
import com.lowaltitude.inspection.mall.application.MallCartController.CheckoutPreviewView;
import com.lowaltitude.inspection.mall.application.MallCartException.Error;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 控制器只做三件事：从令牌里取客户账号、把请求转成服务的参数、把结果和业务拒绝转成契约里的状态码；
 * 结算预览另外把收货地址和“还差什么”（blockers）合进来。
 * 没带令牌时的 401 由安全过滤器给出，在 AuthenticationSecurityIntegrationTest 里检查；这里的 standaloneSetup 不带过滤器。
 */
class MallCartControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOMEONE_ELSE = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000101");
    private static final UUID OFFER = UUID.fromString("c4000000-0000-4000-8000-000000000101");
    private static final UUID LINE = UUID.fromString("c5000000-0000-4000-8000-000000000001");
    private static final UUID SECOND_LINE = UUID.fromString("c5000000-0000-4000-8000-000000000002");
    private static final UUID ADDRESS = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private static final MallMerchantView MERCHANT_VIEW =
            new MallMerchantView(MERCHANT, "示例机器人商家", MallMerchantKind.FACTORY_DIRECT);

    /** 每一种业务拒绝对外的状态码和错误码（GlobalExceptionHandler 与契约 openapi-mall-cart.yaml 1.0.0）。 */
    private static final Map<Error, Rejection> REJECTIONS = Map.of(
            Error.NOT_FOUND, new Rejection(404, "COMMON_NOT_FOUND"),
            Error.NOT_PURCHASABLE, new Rejection(409, "MALL_CART_NOT_PURCHASABLE"),
            Error.NOT_SOLD_IN_CITY, new Rejection(409, "MALL_CART_NOT_SOLD_IN_CITY"),
            Error.CART_FULL, new Rejection(409, "MALL_CART_FULL"),
            Error.MULTIPLE_MERCHANTS, new Rejection(409, "MALL_CHECKOUT_MULTIPLE_MERCHANTS"),
            Error.ITEM_UNAVAILABLE, new Rejection(409, "MALL_CHECKOUT_ITEM_UNAVAILABLE"));

    private final MallCartService carts = mock(MallCartService.class);
    private final AccountAddressService addresses = mock(AccountAddressService.class);
    private final TestingAuthenticationToken customer = signedIn("CUSTOMER");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MallCartController(carts, addresses))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ---- 谁能调 ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void everyRouteRefusesAnAccountThatIsNotACustomer(String route, MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request.principal(signedIn("PROVIDER_OPERATOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(carts, addresses);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void noRouteWorksWithoutASignedInSubject(String route, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(carts, addresses);
    }

    // 账号只来自令牌：请求里再写一个别人的账号也没有用；生产里的 JWT 主体给出同一个账号。
    @Test
    void theAccountComesFromTheTokenAndFromNowhereElse() throws Exception {
        when(carts.count(SUBJECT, "370500")).thenReturn(new CartCountView(2));
        when(carts.add(SUBJECT, OFFER, 1, "370500")).thenReturn(new CartAddedView(LINE, 1, 1));

        mockMvc.perform(get("/api/v1/mall/cart/count")
                        .principal(jwt(List.of("CUSTOMER")))
                        .param("cityCode", "370500")
                        .param("accountId", SOMEONE_ELSE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2));
        mockMvc.perform(post("/api/v1/mall/cart/items")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"%s\",\"quantity\":1,\"cityCode\":\"370500\",\"accountId\":\"%s\"}"
                                .formatted(OFFER, SOMEONE_ELSE)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/mall/cart/count")
                        .principal(jwt(List.of("PROVIDER_ADMIN")))
                        .param("cityCode", "370500"))
                .andExpect(status().isForbidden());

        verify(carts).count(SUBJECT, "370500");
        verify(carts).add(SUBJECT, OFFER, 1, "370500");
    }

    // ---- 购物车 ----

    @Test
    void theCartIsReadForTheTrimmedCityAndReturnedAsTheContractDescribes() throws Exception {
        CartItemView valid = new CartItemView(
                LINE, OFFER, PRODUCT, "编程机器人", "标准版",
                new MallMediaView("https://media.example.com/robot.jpg", "编程机器人"), MERCHANT_VIEW,
                129900L, 2, "东营可买 · 包邮", List.of(DeliveryMode.EXPRESS, DeliveryMode.PICKUP), null);
        CartItemView invalid = new CartItemView(
                SECOND_LINE, UUID.randomUUID(), UUID.randomUUID(), "备用电池", "2000mAh", null, MERCHANT_VIEW,
                null, 1, null, List.of(DeliveryMode.EXPRESS), CartInvalidReason.NOT_SOLD_IN_CITY);
        when(carts.read(SUBJECT, "370500")).thenReturn(new CartView(
                List.of(new CartGroupView(MERCHANT_VIEW, List.of(valid))), List.of(invalid), 1));

        mockMvc.perform(get("/api/v1/mall/cart").principal(customer).param("cityCode", " 370500 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validCount").value(1))
                .andExpect(jsonPath("$.groups[0].merchant.organizationId").value(MERCHANT.toString()))
                .andExpect(jsonPath("$.groups[0].merchant.kind").value("FACTORY_DIRECT"))
                .andExpect(jsonPath("$.groups[0].items[0].length()").value(12))
                .andExpect(jsonPath("$.groups[0].items[0].cartItemId").value(LINE.toString()))
                .andExpect(jsonPath("$.groups[0].items[0].coverImage.url").value("https://media.example.com/robot.jpg"))
                .andExpect(jsonPath("$.groups[0].items[0].unitAmountMinor").value(129900))
                .andExpect(jsonPath("$.groups[0].items[0].deliveryModes[1]").value("PICKUP"))
                .andExpect(jsonPath("$.groups[0].items[0].invalidReason").value(nullValue()))
                .andExpect(jsonPath("$.invalidItems[0].unitAmountMinor").value(nullValue()))
                .andExpect(jsonPath("$.invalidItems[0].coverImage").value(nullValue()))
                .andExpect(jsonPath("$.invalidItems[0].shippingNote").value(nullValue()))
                .andExpect(jsonPath("$.invalidItems[0].invalidReason").value("NOT_SOLD_IN_CITY"));
    }

    @Test
    void addingAnswersCreatedAndAMissingQuantityReachesTheServiceAsZero() throws Exception {
        when(carts.add(SUBJECT, OFFER, 3, "370500")).thenReturn(new CartAddedView(LINE, 5, 2));
        when(carts.add(SUBJECT, OFFER, 0, "370500")).thenThrow(new IllegalArgumentException("cart item is invalid"));

        mockMvc.perform(post("/api/v1/mall/cart/items")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"%s\",\"quantity\":3,\"cityCode\":\" 370500 \"}".formatted(OFFER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cartItemId").value(LINE.toString()))
                .andExpect(jsonPath("$.quantity").value(5))
                .andExpect(jsonPath("$.cartCount").value(2));
        mockMvc.perform(post("/api/v1/mall/cart/items")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"%s\",\"cityCode\":\"370500\"}".formatted(OFFER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verify(carts).add(SUBJECT, OFFER, 0, "370500");
    }

    @Test
    void setQuantityUsesTheLineOfThePathAndAMissingQuantityIsZero() throws Exception {
        when(carts.setQuantity(SUBJECT, LINE, 7)).thenReturn(new CartQuantityView(LINE, 7));
        when(carts.setQuantity(SUBJECT, LINE, 0)).thenThrow(new IllegalArgumentException("quantity is invalid"));

        mockMvc.perform(put("/api/v1/mall/cart/items/{cartItemId}", LINE)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartItemId").value(LINE.toString()))
                .andExpect(jsonPath("$.quantity").value(7));
        mockMvc.perform(put("/api/v1/mall/cart/items/{cartItemId}", LINE)
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(carts).setQuantity(SUBJECT, LINE, 0);
    }

    @Test
    void removePassesTheNamedLinesAndRemoveInvalidPassesTheTrimmedCity() throws Exception {
        when(carts.remove(SUBJECT, List.of(LINE, SECOND_LINE))).thenReturn(new CartRemovedView(2));
        when(carts.removeInvalid(SUBJECT, "370500")).thenReturn(new CartRemovedView(1));

        mockMvc.perform(post("/api/v1/mall/cart/remove")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cartItemIds\":[\"%s\",\"%s\"]}".formatted(LINE, SECOND_LINE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(2));
        mockMvc.perform(post("/api/v1/mall/cart/remove-invalid")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cityCode\":\" 370500\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(1));
    }

    // ---- 结算预览 ----

    @Test
    void anExpressPreviewCarriesTheAddressTheAccountChose() throws Exception {
        when(carts.preview(SUBJECT, "370500", List.of(LINE, SECOND_LINE), null, null, "EXPRESS"))
                .thenReturn(preview(DeliveryMode.EXPRESS));
        when(addresses.findForUse(SUBJECT, ADDRESS)).thenReturn(Optional.of(address()));

        mockMvc.perform(get("/api/v1/mall/checkout-preview")
                        .principal(customer)
                        .param("cityCode", " 370500 ")
                        .param("cartItemIds", LINE.toString(), SECOND_LINE.toString())
                        .param("deliveryMode", "EXPRESS")
                        .param("addressId", ADDRESS.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9))
                .andExpect(jsonPath("$.merchant.name").value("示例机器人商家"))
                .andExpect(jsonPath("$.lines[0].cartItemId").value(LINE.toString()))
                .andExpect(jsonPath("$.lines[0].subtotalMinor").value(259800))
                .andExpect(jsonPath("$.pieceCount").value(2))
                .andExpect(jsonPath("$.fulfilledFrom").value("示例机器人商家 东营仓"))
                .andExpect(jsonPath("$.deliveryOptions[0].mode").value("EXPRESS"))
                .andExpect(jsonPath("$.deliveryOptions[1].note").value("东营自提点"))
                .andExpect(jsonPath("$.deliveryMode").value("EXPRESS"))
                .andExpect(jsonPath("$.address.addressId").value(ADDRESS.toString()))
                .andExpect(jsonPath("$.address.region.cityName").value("东营市"))
                .andExpect(jsonPath("$.address.isDefault").value(true))
                .andExpect(jsonPath("$.amounts.goodsMinor").value(259800))
                .andExpect(jsonPath("$.amounts.shippingMinor").value(1200))
                .andExpect(jsonPath("$.amounts.payableMinor").value(261000))
                .andExpect(jsonPath("$.amounts.currency").value("CNY"))
                .andExpect(jsonPath("$.blockers").isEmpty());

        verify(addresses).findForUse(SUBJECT, ADDRESS);
    }

    @Test
    void anExpressPreviewWithoutAnyAddressAsksForOne() throws Exception {
        when(carts.preview(SUBJECT, "370500", null, OFFER, 2, null)).thenReturn(preview(DeliveryMode.EXPRESS));
        when(addresses.findForUse(SUBJECT, null)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/mall/checkout-preview")
                        .principal(customer)
                        .param("cityCode", "370500")
                        .param("offerId", OFFER.toString())
                        .param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value(nullValue()))
                .andExpect(jsonPath("$.blockers.length()").value(1))
                .andExpect(jsonPath("$.blockers[0]").value("ADDRESS_REQUIRED"));

        verify(addresses).findForUse(SUBJECT, null);
    }

    @Test
    void aPickupPreviewNeverReadsAnAddress() throws Exception {
        when(carts.preview(SUBJECT, "370500", List.of(LINE), null, null, "PICKUP"))
                .thenReturn(preview(DeliveryMode.PICKUP));

        mockMvc.perform(get("/api/v1/mall/checkout-preview")
                        .principal(customer)
                        .param("cityCode", "370500")
                        .param("cartItemIds", LINE.toString())
                        .param("deliveryMode", "PICKUP")
                        .param("addressId", ADDRESS.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryMode").value("PICKUP"))
                .andExpect(jsonPath("$.address").value(nullValue()))
                .andExpect(jsonPath("$.blockers").isEmpty());

        verifyNoInteractions(addresses);
    }

    @Test
    void aPreviewWithoutACommonDeliveryModeSaysSoAndReadsNoAddress() throws Exception {
        when(carts.preview(SUBJECT, "370500", List.of(LINE, SECOND_LINE), null, null, null))
                .thenReturn(preview(null));

        // 逗号分隔的写法和重复参数的写法一样。
        mockMvc.perform(get("/api/v1/mall/checkout-preview")
                        .principal(customer)
                        .param("cityCode", "370500")
                        .param("cartItemIds", LINE + "," + SECOND_LINE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryMode").value(nullValue()))
                .andExpect(jsonPath("$.address").value(nullValue()))
                .andExpect(jsonPath("$.blockers.length()").value(1))
                .andExpect(jsonPath("$.blockers[0]").value("NO_DELIVERY_OPTION"));

        verifyNoInteractions(addresses);
    }

    @Test
    void someoneElsesAddressInAnExpressPreviewIsNotFound() throws Exception {
        when(carts.preview(SUBJECT, "370500", List.of(LINE), null, null, null))
                .thenReturn(preview(DeliveryMode.EXPRESS));
        when(addresses.findForUse(SUBJECT, ADDRESS)).thenThrow(new AddressNotFoundException());

        mockMvc.perform(get("/api/v1/mall/checkout-preview")
                        .principal(customer)
                        .param("cityCode", "370500")
                        .param("cartItemIds", LINE.toString())
                        .param("addressId", ADDRESS.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }

    @Test
    void theBlockersFollowTheDeliveryModeAndTheAddress() {
        CheckoutPreviewView express = CheckoutPreviewView.of(preview(DeliveryMode.EXPRESS), address());
        CheckoutPreviewView noAddress = CheckoutPreviewView.of(preview(DeliveryMode.EXPRESS), null);
        CheckoutPreviewView pickup = CheckoutPreviewView.of(preview(DeliveryMode.PICKUP), null);
        CheckoutPreviewView none = CheckoutPreviewView.of(preview(null), null);

        assertThat(express.blockers()).isEmpty();
        assertThat(noAddress.blockers()).containsExactly(CheckoutBlocker.ADDRESS_REQUIRED);
        assertThat(pickup.blockers()).isEmpty();
        assertThat(none.blockers()).containsExactly(CheckoutBlocker.NO_DELIVERY_OPTION);
        // 其余字段原样来自服务的预览。
        CheckoutPreview source = preview(DeliveryMode.EXPRESS);
        assertThat(express.merchant()).isEqualTo(source.merchant());
        assertThat(express.lines()).isEqualTo(source.lines());
        assertThat(express.deliveryOptions()).isEqualTo(source.deliveryOptions());
        assertThat(express.amounts()).isEqualTo(source.amounts());
        assertThat(express.address()).isEqualTo(address());
    }

    // ---- 拒绝与参数错误 ----

    @ParameterizedTest
    @EnumSource(Error.class)
    void everyBusinessRejectionMapsToItsStatusAndCode(Error error) throws Exception {
        Rejection expected = REJECTIONS.get(error);
        assertThat(expected).as("no expected status and code for " + error).isNotNull();
        when(carts.preview(SUBJECT, "370500", null, OFFER, 1, null)).thenThrow(new MallCartException(error));

        mockMvc.perform(get("/api/v1/mall/checkout-preview")
                        .principal(customer)
                        .header("X-Request-Id", "trace-1")
                        .param("cityCode", "370500")
                        .param("offerId", OFFER.toString())
                        .param("quantity", "1"))
                .andExpect(status().is(expected.status()))
                .andExpect(jsonPath("$.code").value(expected.code()))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.details").value(nullValue()));

        verifyNoInteractions(addresses);
    }

    // 每个错误码只对应一种拒绝，客户端可以只看 code。
    @Test
    void everyMallCartErrorCodeBelongsToOneRejection() {
        assertThat(REJECTIONS).hasSize(Error.values().length);
        assertThat(REJECTIONS.values().stream().map(Rejection::code)).doesNotHaveDuplicates();
        assertThat(REJECTIONS.values().stream().map(Rejection::code).filter(code -> code.startsWith("MALL_")))
                .hasSize(5);
    }

    @Test
    void malformedIdsNumbersAndBodiesAreBadRequests() throws Exception {
        when(carts.read(SUBJECT, null)).thenThrow(new IllegalArgumentException("city code is invalid"));

        mockMvc.perform(get("/api/v1/mall/cart").principal(customer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(put("/api/v1/mall/cart/items/not-a-uuid")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/mall/checkout-preview")
                        .principal(customer)
                        .param("cityCode", "370500")
                        .param("cartItemIds", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/mall/checkout-preview")
                        .principal(customer)
                        .param("cityCode", "370500")
                        .param("offerId", OFFER.toString())
                        .param("quantity", "two"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/mall/cart/items")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerId\":\"not-a-uuid\",\"quantity\":1,\"cityCode\":\"370500\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/mall/cart/remove")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));

        verifyNoInteractions(addresses);
    }

    // ---- 辅助 ----

    private static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of("GET cart", get("/api/v1/mall/cart").param("cityCode", "370500")),
                Arguments.of("GET cart count", get("/api/v1/mall/cart/count").param("cityCode", "370500")),
                Arguments.of("POST cart items", json(post("/api/v1/mall/cart/items"),
                        "{\"offerId\":\"%s\",\"quantity\":1,\"cityCode\":\"370500\"}".formatted(OFFER))),
                Arguments.of("PUT cart item", json(put("/api/v1/mall/cart/items/" + LINE), "{\"quantity\":2}")),
                Arguments.of("POST cart remove", json(post("/api/v1/mall/cart/remove"),
                        "{\"cartItemIds\":[\"%s\"]}".formatted(LINE))),
                Arguments.of("POST cart remove-invalid", json(post("/api/v1/mall/cart/remove-invalid"),
                        "{\"cityCode\":\"370500\"}")),
                Arguments.of("GET checkout-preview", get("/api/v1/mall/checkout-preview")
                        .param("cityCode", "370500")
                        .param("cartItemIds", LINE.toString())));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static CheckoutPreview preview(DeliveryMode mode) {
        long shipping = mode == DeliveryMode.EXPRESS ? 1200 : 0;
        List<DeliveryOptionView> options = mode == null
                ? List.of()
                : List.of(new DeliveryOptionView(DeliveryMode.EXPRESS, "东营可买 · 包邮"),
                        new DeliveryOptionView(DeliveryMode.PICKUP, "东营自提点"));
        return new CheckoutPreview(
                MERCHANT_VIEW,
                List.of(new CheckoutLineView(LINE, OFFER, PRODUCT, "编程机器人", "标准版", null, 129900, 2, 259800)),
                2,
                "示例机器人商家 东营仓",
                options,
                mode,
                new CheckoutAmountsView(259800, shipping, 259800 + shipping, "CNY"));
    }

    private static AddressView address() {
        return new AddressView(
                ADDRESS,
                "王女士",
                "13800138000",
                new AddressRegionView("370000", "山东省", "370500", "东营市"),
                "东营区示例路 18 号",
                true);
    }

    private static TestingAuthenticationToken signedIn(String role) {
        return new TestingAuthenticationToken(new CurrentSubject(SUBJECT, Set.of(role), null, Set.of(), 0), null);
    }

    private static TestingAuthenticationToken jwt(List<String> roles) {
        return new TestingAuthenticationToken(Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .claim("subjectId", SUBJECT.toString())
                .claim("roles", roles)
                .build(), null);
    }

    private record Rejection(int status, String code) {
    }
}
