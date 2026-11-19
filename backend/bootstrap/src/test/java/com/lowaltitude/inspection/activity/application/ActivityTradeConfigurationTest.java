package com.lowaltitude.inspection.activity.application;

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

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.trade.application.TradeSourceListener;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.junit.jupiter.api.Test;

/**
 * 收费报名接到交易模块的那一层（E09）：报名的内容原样变成一张 ACTIVITY 订单，交易模块的拒绝换成报名的错误，
 * 付款和关闭转给活动服务。
 */
class ActivityTradeConfigurationTest {

    private static final UUID ENROLLMENT = UUID.fromString("e1000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORGANIZER = UUID.fromString("a1000000-0000-4000-8000-000000000001");
    private static final UUID ACTIVITY = UUID.fromString("a2000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("a3000000-0000-4000-8000-000000000101");
    private static final UUID ORDER = UUID.fromString("b1000000-0000-4000-8000-000000000001");
    private static final Instant PAY_BEFORE = Instant.parse("2026-11-19T02:15:00Z");

    private final TradeSourceOrders orders = mock(TradeSourceOrders.class);
    private final ActivityPaymentPort port = new ActivityTradeConfiguration().activityPaymentPort(orders);

    @Test
    void anEnrollmentBecomesOneActivityOrderLine() {
        when(orders.open(any())).thenReturn(new TradeSourceOrders.OpenedOrder(ORDER, "AO2026111900000001", PAY_BEFORE));

        ActivityPaymentPort.OpenedPayment opened = port.open(request());

        assertThat(opened).isEqualTo(new ActivityPaymentPort.OpenedPayment(ORDER, PAY_BEFORE));
        verify(orders).open(new TradeSourceOrders.OpenCommand("ACTIVITY", ENROLLMENT, ACCOUNT, ORGANIZER, "370500",
                List.of(new TradeSourceOrders.Line(ACTIVITY, SESSION, "青少年无人机编程挑战赛", "11月28日 09:00 上午场",
                        null, 12_000, 2)),
                PAY_BEFORE));
    }

    @Test
    void tradeRefusalsBecomeEnrollmentErrors() {
        when(orders.open(any())).thenThrow(new TradeException(TradeException.Error.MERCHANT_PAYMENT_UNAVAILABLE));
        assertRejected(Error.PAYMENT_UNAVAILABLE, () -> port.open(request()));

        for (TradeException.Error refused : List.of(TradeException.Error.REFUND_EXCEEDS_PAID,
                TradeException.Error.REFUND_LIMIT_REACHED, TradeException.Error.ORDER_STATE_INVALID)) {
            doThrow(new TradeException(refused))
                    .when(orders).requestRefund(any(), any(Long.class), any(), any(), any());
            assertRejected(Error.CANCEL_NOT_ALLOWED,
                    () -> port.requestRefund(ORDER, 12_000, "取消活动报名 1 人", UUID.randomUUID(), ACCOUNT));
        }
        // 别的（比如订单不存在）原样交给全局异常处理。
        doThrow(new TradeException(TradeException.Error.NOT_FOUND))
                .when(orders).requestRefund(any(), any(Long.class), any(), any(), any());
        assertThatThrownBy(() -> port.requestRefund(ORDER, 12_000, "取消", UUID.randomUUID(), ACCOUNT))
                .isInstanceOf(TradeException.class);
    }

    @Test
    void refundStatesAndTheOtherCallsGoStraightThrough() {
        UUID refund = UUID.randomUUID();
        when(orders.refundStates(List.of(refund)))
                .thenReturn(List.of(new TradeSourceOrders.RefundState(refund, "SUCCEEDED", 12_000)));
        when(orders.merchantCanCollect(ORGANIZER)).thenReturn(true);

        assertThat(port.refundStates(List.of(refund)))
                .isEqualTo(Map.of(refund, new ActivityPaymentPort.RefundProgress("SUCCEEDED", 12_000)));
        assertThat(port.merchantCanCollect(ORGANIZER)).isTrue();
        port.lockOrder(ORDER);
        port.cancelUnpaid(ACCOUNT, ORDER);

        verify(orders).lockOrder(ORDER);
        verify(orders).cancelUnpaid(ACCOUNT, ORDER);
    }

    @Test
    void theListenerHandsActivityPaymentsToTheEnrollmentService() {
        ActivityEnrollmentService enrollments = mock(ActivityEnrollmentService.class);
        when(enrollments.onPaymentConfirmed(ORDER, ENROLLMENT)).thenReturn(false);
        TradeSourceListener listener = new ActivityTradeConfiguration().activityTradeSourceListener(enrollments);

        assertThat(listener.source()).isEqualTo("ACTIVITY");
        assertThat(listener.onPaid(ORDER, ENROLLMENT)).isFalse();
        listener.onClosed(ORDER, ENROLLMENT);

        verify(enrollments).onPaymentConfirmed(ORDER, ENROLLMENT);
        verify(enrollments).onPaymentClosed(ORDER, ENROLLMENT);
    }

    private static ActivityPaymentPort.OpenPayment request() {
        return new ActivityPaymentPort.OpenPayment(ENROLLMENT, ACCOUNT, ORGANIZER, "370500", ACTIVITY, SESSION,
                "青少年无人机编程挑战赛", "11月28日 09:00 上午场", null, 12_000, 2, PAY_BEFORE);
    }

    private static void assertRejected(Error error, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ActivityEnrollmentException.class,
                rejected -> assertThat(rejected.error()).isEqualTo(error));
    }
}
