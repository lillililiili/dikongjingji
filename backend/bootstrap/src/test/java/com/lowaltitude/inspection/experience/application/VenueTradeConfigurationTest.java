package com.lowaltitude.inspection.experience.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.VenueBookingException.Error;
import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.trade.application.TradeSourceListener;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.junit.jupiter.api.Test;

/**
 * 到馆预约接到交易模块的那一层（E10）：预约变成一张 VENUE 订单，收款方是场馆运营机构；交易模块的拒绝换成
 * 预约的错误；付款和关闭转给预约服务。
 */
class VenueTradeConfigurationTest {

    private static final UUID BOOKING = UUID.fromString("b6000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OPERATOR_ORG = UUID.fromString("a1000000-0000-4000-8000-000000000004");
    private static final UUID PROJECT = UUID.fromString("b4000000-0000-4000-8000-000000000101");
    private static final UUID SESSION = UUID.fromString("b5000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("d0000000-0000-4000-8000-000000000001");
    private static final Instant PAY_BEFORE = Instant.parse("2026-11-19T02:15:00Z");

    private final TradeSourceOrders orders = mock(TradeSourceOrders.class);
    private final VenuePaymentPort port = new VenueTradeConfiguration().venuePaymentPort(orders);

    @Test
    void aBookingBecomesOneVenueOrderLine() {
        when(orders.open(any())).thenReturn(new TradeSourceOrders.OpenedOrder(ORDER, "VO2026111900000001", PAY_BEFORE));

        assertThat(port.open(request())).isEqualTo(new VenuePaymentPort.OpenedPayment(ORDER, PAY_BEFORE));

        verify(orders).open(new TradeSourceOrders.OpenCommand("VENUE", BOOKING, ACCOUNT, OPERATOR_ORG, "370500",
                List.of(new TradeSourceOrders.Line(PROJECT, SESSION, "东营低空与机器人体验馆 机器人避障闯关",
                        "11月21日 10:00", null, 3900, 2)),
                PAY_BEFORE));
    }

    @Test
    void tradeRefusalsBecomeBookingErrors() {
        when(orders.open(any())).thenThrow(new TradeException(TradeException.Error.MERCHANT_PAYMENT_UNAVAILABLE));
        assertRejected(Error.PAYMENT_UNAVAILABLE, () -> port.open(request()));

        for (TradeException.Error refused : List.of(TradeException.Error.REFUND_EXCEEDS_PAID,
                TradeException.Error.REFUND_LIMIT_REACHED, TradeException.Error.ORDER_STATE_INVALID)) {
            doThrow(new TradeException(refused))
                    .when(orders).requestRefund(any(), any(Long.class), any(), any(), any());
            assertRejected(Error.CANCEL_NOT_ALLOWED,
                    () -> port.requestRefund(ORDER, 7800, "取消到馆预约", UUID.randomUUID(), ACCOUNT));
        }
        doThrow(new TradeException(TradeException.Error.NOT_FOUND))
                .when(orders).requestRefund(any(), any(Long.class), any(), any(), any());
        assertThatThrownBy(() -> port.requestRefund(ORDER, 7800, "取消到馆预约", UUID.randomUUID(), ACCOUNT))
                .isInstanceOf(TradeException.class);
    }

    @Test
    void refundStatesAndTheOtherCallsGoStraightThrough() {
        UUID refund = UUID.randomUUID();
        when(orders.refundStates(List.of(refund)))
                .thenReturn(List.of(new TradeSourceOrders.RefundState(refund, "PROCESSING", 7800)));
        when(orders.merchantCanCollect(OPERATOR_ORG)).thenReturn(true);

        assertThat(port.refundStates(List.of(refund)))
                .isEqualTo(Map.of(refund, new VenuePaymentPort.RefundProgress("PROCESSING", 7800)));
        assertThat(port.merchantCanCollect(OPERATOR_ORG)).isTrue();
        port.lockOrder(ORDER);
        port.cancelUnpaid(ACCOUNT, ORDER);

        verify(orders).lockOrder(ORDER);
        verify(orders).cancelUnpaid(ACCOUNT, ORDER);
    }

    @Test
    void theListenerHandsVenuePaymentsToTheBookingService() {
        VenueBookingService bookings = mock(VenueBookingService.class);
        when(bookings.onPaymentConfirmed(ORDER, BOOKING)).thenReturn(true);
        TradeSourceListener listener = new VenueTradeConfiguration().venueTradeSourceListener(bookings);

        assertThat(listener.source()).isEqualTo("VENUE");
        assertThat(listener.onPaid(ORDER, BOOKING)).isTrue();
        listener.onClosed(ORDER, BOOKING);

        verify(bookings).onPaymentConfirmed(ORDER, BOOKING);
        verify(bookings).onPaymentClosed(ORDER, BOOKING);
    }

    private static VenuePaymentPort.OpenPayment request() {
        return new VenuePaymentPort.OpenPayment(BOOKING, ACCOUNT, OPERATOR_ORG, "370500", PROJECT, SESSION,
                "东营低空与机器人体验馆 机器人避障闯关", "11月21日 10:00", null, 3900, 2, PAY_BEFORE);
    }

    private static void assertRejected(Error error, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(VenueBookingException.class,
                rejected -> assertThat(rejected.error()).isEqualTo(error));
    }
}
