package com.lowaltitude.inspection.rental.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.account.application.AccountAddressService;
import com.lowaltitude.inspection.account.application.AddressNotFoundException;
import com.lowaltitude.inspection.account.application.AddressRegionView;
import com.lowaltitude.inspection.account.application.AddressView;
import com.lowaltitude.inspection.mall.application.MallRentalOffers;
import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.trade.application.TradeSourceListener;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 租赁模块的端口（F3）接到商城、账号和交易模块：字段原样转过去，交易模块的拒绝换成 RENTAL_ 的错误。 */
class RentalPortsConfigurationTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ADDRESS = UUID.fromString("a0000000-0000-4000-8000-000000000001");
    private static final UUID OFFER = UUID.fromString("7344eb2d-bf1d-aa17-7e27-fb2351e99dee");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000401");
    private static final UUID ORDER = UUID.fromString("e2000000-0000-4000-8000-000000000001");
    private static final UUID TRADE = UUID.fromString("e3000000-0000-4000-8000-000000000001");

    private final RentalPortsConfiguration configuration = new RentalPortsConfiguration();

    @Test
    void offersComeFromTheMall() {
        MallRentalOffers mall = mock(MallRentalOffers.class);
        when(mall.find(OFFER)).thenReturn(Optional.of(new MallRentalOffers.RentalOffer(OFFER, PRODUCT, "巡检机器狗", "标准版",
                null, MERCHANT, "示例机器人商家", "FACTORY_DIRECT", "370500", 38000L, "DAY", "东营仓", true)));

        assertThat(configuration.rentalOffers(mall).find(OFFER)).contains(new RentalPorts.Offer(OFFER, PRODUCT, "巡检机器狗",
                "标准版", null, MERCHANT, "示例机器人商家", "370500", 38000L, "DAY", true));
        assertThat(configuration.rentalOffers(mall).find(UUID.randomUUID())).isEmpty();
    }

    @Test
    void addressesAreTheAccountsOwnAndSomeoneElsesAreMissing() {
        AccountAddressService addresses = mock(AccountAddressService.class);
        when(addresses.findForUse(ACCOUNT, ADDRESS)).thenReturn(Optional.of(new AddressView(ADDRESS, "王女士",
                "13800001234", new AddressRegionView("370000", "山东省", "370500", "东营市"), "东营港 6 号", true)));
        UUID foreign = UUID.randomUUID();
        when(addresses.findForUse(ACCOUNT, foreign)).thenThrow(new AddressNotFoundException());
        RentalPorts.Addresses port = configuration.rentalAddresses(addresses);

        assertThat(port.find(ACCOUNT, ADDRESS)).contains(new RentalPorts.Address(ADDRESS, "王女士", "13800001234", "山东省",
                "370500", "东营市", "东营港 6 号"));
        assertThat(port.find(ACCOUNT, foreign)).isEmpty();
        // 不带地址时不能用默认地址顶替。
        assertThat(port.find(ACCOUNT, null)).isEmpty();
    }

    @Test
    void paymentsOpenRentalOrdersAndTranslateTradeRefusals() {
        TradeSourceOrders orders = mock(TradeSourceOrders.class);
        Instant payBefore = Instant.parse("2026-11-19T02:30:00Z");
        when(orders.open(any())).thenReturn(new TradeSourceOrders.OpenedOrder(TRADE, "RO2026111912345678", payBefore));
        RentalPorts.Payments payments = configuration.rentalPayments(orders);

        RentalPorts.OpenedPayment opened = payments.open(new RentalPorts.OpenPayment(ORDER, ACCOUNT, MERCHANT, "370500",
                List.of(new RentalPorts.PaymentLine(PRODUCT, OFFER, "巡检机器狗", "11月22日–11月26日 共 5 天", null, 190000, 1)),
                payBefore));

        assertThat(opened).isEqualTo(new RentalPorts.OpenedPayment(TRADE, payBefore));
        ArgumentCaptor<TradeSourceOrders.OpenCommand> command = ArgumentCaptor.forClass(TradeSourceOrders.OpenCommand.class);
        verify(orders).open(command.capture());
        assertThat(command.getValue().source()).isEqualTo("RENTAL");
        assertThat(command.getValue().sourceRef()).isEqualTo(ORDER);
        assertThat(command.getValue().merchantId()).isEqualTo(MERCHANT);
        assertThat(command.getValue().lines()).containsExactly(new TradeSourceOrders.Line(PRODUCT, OFFER, "巡检机器狗",
                "11月22日–11月26日 共 5 天", null, 190000, 1));

        when(orders.open(any())).thenThrow(new TradeException(TradeException.Error.MERCHANT_PAYMENT_UNAVAILABLE));
        assertThatThrownBy(() -> payments.open(new RentalPorts.OpenPayment(ORDER, ACCOUNT, MERCHANT, "370500", List.of(),
                payBefore))).isInstanceOfSatisfying(RentalException.class,
                exception -> assertThat(exception.error()).isEqualTo(RentalException.Error.PAYMENT_UNAVAILABLE));
        when(orders.requestRefund(any(), any(Long.class), any(), any(), any()))
                .thenThrow(new TradeException(TradeException.Error.ORDER_STATE_INVALID));
        assertThatThrownBy(() -> payments.requestRefund(TRADE, 1, "r", UUID.randomUUID(), ACCOUNT))
                .isInstanceOfSatisfying(RentalException.class,
                        exception -> assertThat(exception.error()).isEqualTo(RentalException.Error.REFUND_NOT_ALLOWED));
        when(orders.refundStates(List.of(TRADE))).thenReturn(List.of(new TradeSourceOrders.RefundState(TRADE, "SUCCEEDED", 5)));
        assertThat(payments.refundStates(List.of(TRADE))).containsEntry(TRADE, new RentalPorts.RefundProgress("SUCCEEDED", 5));
    }

    @Test
    void theTradeListenerHandlesRentalOrders() {
        RentalService rentals = mock(RentalService.class);
        when(rentals.onPaymentConfirmed(TRADE, ORDER)).thenReturn(false);
        TradeSourceListener listener = configuration.rentalTradeSourceListener(rentals);

        assertThat(listener.source()).isEqualTo("RENTAL");
        assertThat(listener.onPaid(TRADE, ORDER)).isFalse();
        listener.onClosed(TRADE, ORDER);
        verify(rentals).onPaymentClosed(TRADE, ORDER);
    }
}
