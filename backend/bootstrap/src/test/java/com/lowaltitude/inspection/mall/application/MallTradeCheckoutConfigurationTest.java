package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.account.application.AccountAddressService;
import com.lowaltitude.inspection.account.application.AddressNotFoundException;
import com.lowaltitude.inspection.account.application.AddressRegionView;
import com.lowaltitude.inspection.account.application.AddressView;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.AddressSnapshot;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.CheckoutQuote;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.CheckoutRequest;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort.QuotedLine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 交易模块的报价由商城的确认订单预览和地址簿拼出来：金额、行、配送方式原样取自预览；
 * 快递时才去地址簿取地址（指定的或默认的），自提点取自预览里的自提选项；下单后删购物车行交给商城。
 */
class MallTradeCheckoutConfigurationTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID CART_A = UUID.fromString("c5000000-0000-4000-8000-000000000001");
    private static final UUID CART_B = UUID.fromString("c5000000-0000-4000-8000-000000000002");
    private static final UUID OFFER_A = UUID.fromString("c4000000-0000-4000-8000-000000000001");
    private static final UUID OFFER_B = UUID.fromString("c4000000-0000-4000-8000-000000000002");
    private static final UUID PRODUCT_A = UUID.fromString("c2000000-0000-4000-8000-000000000001");
    private static final UUID PRODUCT_B = UUID.fromString("c2000000-0000-4000-8000-000000000002");
    private static final UUID ADDRESS_ID = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private static final AddressView ADDRESS = new AddressView(
            ADDRESS_ID,
            "王女士",
            "13800138000",
            new AddressRegionView("370000", "山东省", "370500", "东营市"),
            "东营区示例路 18 号",
            true);

    private final MallCartService carts = mock(MallCartService.class);
    private final AccountAddressService addresses = mock(AccountAddressService.class);
    private final TradeCheckoutPort port = new MallTradeCheckoutConfiguration().mallTradeCheckout(carts, addresses);

    @Test
    void anExpressQuoteTakesTheAmountsAndLinesFromThePreviewAndTheChosenAddress() {
        CheckoutRequest request = new CheckoutRequest(
                "370500", List.of(CART_A, CART_B), null, null, "EXPRESS", ADDRESS_ID);
        when(carts.preview(ACCOUNT, "370500", List.of(CART_A, CART_B), null, null, "EXPRESS"))
                .thenReturn(preview(DeliveryMode.EXPRESS, bothOptions()));
        when(addresses.findForUse(ACCOUNT, ADDRESS_ID)).thenReturn(Optional.of(ADDRESS));

        CheckoutQuote quote = port.quote(ACCOUNT, request);

        assertThat(quote.merchantId()).isEqualTo(MERCHANT);
        assertThat(quote.merchantName()).isEqualTo("示例无人机专卖店");
        assertThat(quote.merchantKind()).isEqualTo("LOCAL_STORE");
        assertThat(quote.deliveryMode()).isEqualTo("EXPRESS");
        assertThat(quote.address()).isEqualTo(
                new AddressSnapshot("王女士", "13800138000", "山东省", "东营市", "东营区示例路 18 号"));
        assertThat(quote.goodsMinor()).isEqualTo(140_000);
        assertThat(quote.shippingMinor()).isEqualTo(1_000);
        assertThat(quote.payableMinor()).isEqualTo(141_000);
        assertThat(quote.lines()).containsExactly(
                new QuotedLine(PRODUCT_A, OFFER_A, "示例无人机", "标准版", "https://img.example.com/a.png",
                        100_000, 1, 100_000),
                new QuotedLine(PRODUCT_B, OFFER_B, "备用电池", "5000mAh", null, 20_000, 2, 40_000));
        verify(addresses).findForUse(ACCOUNT, ADDRESS_ID);
    }

    @Test
    void anExpressQuoteWithoutAnAddressIdUsesTheDefaultAndHasNoAddressWhenThereIsNone() {
        when(carts.preview(ACCOUNT, "370500", null, OFFER_A, 2, "EXPRESS"))
                .thenReturn(preview(DeliveryMode.EXPRESS, List.of(new DeliveryOptionView(DeliveryMode.EXPRESS, null))));
        when(addresses.findForUse(ACCOUNT, null)).thenReturn(Optional.empty());

        CheckoutQuote quote = port.quote(ACCOUNT, new CheckoutRequest("370500", null, OFFER_A, 2, "EXPRESS", null));

        assertThat(quote.deliveryMode()).isEqualTo("EXPRESS");
        assertThat(quote.address()).isNull();
        assertThat(quote.pickupPoint()).isNull();
        verify(addresses).findForUse(ACCOUNT, null);
    }

    @Test
    void aPickupQuoteHasThePickupPointAndNeverReadsTheAddressBook() {
        when(carts.preview(ACCOUNT, "370500", List.of(CART_A), null, null, "PICKUP"))
                .thenReturn(preview(DeliveryMode.PICKUP, bothOptions()));

        CheckoutQuote quote = port.quote(
                ACCOUNT, new CheckoutRequest("370500", List.of(CART_A), null, null, "PICKUP", ADDRESS_ID));

        assertThat(quote.deliveryMode()).isEqualTo("PICKUP");
        assertThat(quote.pickupPoint()).isEqualTo("东营区示例路 1 号门店");
        assertThat(quote.address()).isNull();
        verifyNoInteractions(addresses);
    }

    // 要的方式不可用时预览会换成可用的那种，报价照实给出，由交易模块判断“配送方式不可用”。
    @Test
    void theModeTheMallCanDoIsPassedOnEvenWhenItIsNotTheOneAskedFor() {
        when(carts.preview(ACCOUNT, "370500", List.of(CART_A), null, null, "EXPRESS"))
                .thenReturn(preview(DeliveryMode.PICKUP,
                        List.of(new DeliveryOptionView(DeliveryMode.PICKUP, "东营区示例路 1 号门店"))));

        CheckoutQuote quote = port.quote(
                ACCOUNT, new CheckoutRequest("370500", List.of(CART_A), null, null, "EXPRESS", ADDRESS_ID));

        assertThat(quote.deliveryMode()).isEqualTo("PICKUP");
        assertThat(quote.address()).isNull();
        verifyNoInteractions(addresses);
    }

    @Test
    void anOrderNoModeCanDeliverHasNoModeNoPickupPointAndNoAddress() {
        when(carts.preview(ACCOUNT, "370500", List.of(CART_A), null, null, "EXPRESS"))
                .thenReturn(preview(null, List.of()));

        CheckoutQuote quote = port.quote(
                ACCOUNT, new CheckoutRequest("370500", List.of(CART_A), null, null, "EXPRESS", ADDRESS_ID));

        assertThat(quote.deliveryMode()).isNull();
        assertThat(quote.pickupPoint()).isNull();
        assertThat(quote.address()).isNull();
        verifyNoInteractions(addresses);
    }

    @Test
    void refusalsFromTheMallAndTheAddressBookAreNotSwallowed() {
        when(carts.preview(ACCOUNT, "370500", List.of(CART_A), null, null, "EXPRESS"))
                .thenThrow(new MallCartException(MallCartException.Error.MULTIPLE_MERCHANTS));
        assertThatThrownBy(() -> port.quote(
                ACCOUNT, new CheckoutRequest("370500", List.of(CART_A), null, null, "EXPRESS", ADDRESS_ID)))
                .isInstanceOf(MallCartException.class);

        when(carts.preview(ACCOUNT, "370500", List.of(CART_B), null, null, "EXPRESS"))
                .thenReturn(preview(DeliveryMode.EXPRESS, bothOptions()));
        when(addresses.findForUse(ACCOUNT, ADDRESS_ID)).thenThrow(new AddressNotFoundException());
        assertThatThrownBy(() -> port.quote(
                ACCOUNT, new CheckoutRequest("370500", List.of(CART_B), null, null, "EXPRESS", ADDRESS_ID)))
                .isInstanceOf(AddressNotFoundException.class);
    }

    @Test
    void removingCartLinesIsHandedToTheCart() {
        port.removeCartLines(ACCOUNT, List.of(CART_A, CART_B));

        verify(carts).remove(ACCOUNT, List.of(CART_A, CART_B));
        verifyNoInteractions(addresses);
    }

    // 和交易模块的服务一样，只在有数据库时装配。
    @Test
    void theAdapterIsOnlyWiredWhenThereIsADatabase() {
        ConditionalOnProperty condition = MallTradeCheckoutConfiguration.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition.prefix()).isEqualTo("spring.datasource");
        assertThat(condition.name()).containsExactly("url");
    }

    // ---- 辅助 ----

    private static List<DeliveryOptionView> bothOptions() {
        return List.of(
                new DeliveryOptionView(DeliveryMode.EXPRESS, "顺丰包邮"),
                new DeliveryOptionView(DeliveryMode.PICKUP, "东营区示例路 1 号门店"));
    }

    private static CheckoutPreview preview(DeliveryMode mode, List<DeliveryOptionView> options) {
        long shipping = mode == DeliveryMode.EXPRESS ? 1_000 : 0;
        return new CheckoutPreview(
                new MallMerchantView(MERCHANT, "示例无人机专卖店", MallMerchantKind.LOCAL_STORE),
                List.of(
                        new CheckoutLineView(CART_A, OFFER_A, PRODUCT_A, "示例无人机", "标准版",
                                new MallMediaView("https://img.example.com/a.png", "示例无人机"), 100_000, 1, 100_000),
                        new CheckoutLineView(CART_B, OFFER_B, PRODUCT_B, "备用电池", "5000mAh", null, 20_000, 2, 40_000)),
                3,
                "东营仓",
                options,
                mode,
                new CheckoutAmountsView(140_000, shipping, 140_000 + shipping, "CNY"));
    }
}
