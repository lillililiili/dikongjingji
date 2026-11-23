package com.lowaltitude.inspection.application.serviceflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S4-1 付款节点和取消（L4 关键链路）：金额、幂等、重复回调、关闭后到账、取消退款。
 */
class InspectionPaymentServiceTest {

    static final Instant NOW = Instant.parse("2026-11-22T02:00:00Z");
    static final UUID CUSTOMER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    static final UUID PROVIDER = UUID.fromString("20000000-0000-4000-8000-000000000002");

    private FakeServiceFlow flow;
    private FakeTrade trade;
    private InspectionPaymentService service;

    @BeforeEach
    void setUp() {
        flow = new FakeServiceFlow();
        trade = new FakeTrade();
        service = new InspectionPaymentService(flow, flow, () -> trade, FakeServiceFlow.DIRECT,
                Clock.fixed(NOW, ZoneOffset.UTC));
        // 交易模块关单后回调巡检（真实流程里由 TradeSourceListener 转过来）。
        trade.onClosed = (tradeOrderId, attemptId) -> service.onPaymentClosed(tradeOrderId, attemptId);
    }

    // ---- 发起付款 ----

    @Test
    void theDownPaymentOpensOneTradeOrderForItsAmountAndReusesIt() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 558_000L, List.of(217_000L, 217_000L));

        PaymentLaunchView first = service.payNode(customer(), orderId, 1, "r1");
        PaymentLaunchView again = service.payNode(customer(), orderId, 1, "r2");

        assertThat(trade.opened).hasSize(1);
        InspectionPaymentPort.OpenPayment opened = trade.opened.get(0);
        assertThat(opened.amountMinor()).isEqualTo(558_000L);
        assertThat(opened.providerId()).isEqualTo(PROVIDER);
        assertThat(opened.customerId()).isEqualTo(CUSTOMER);
        assertThat(opened.nodeLabel()).isEqualTo("首付 30%");
        assertThat(opened.orderTitle()).isEqualTo("物流园区季度安防巡检");
        assertThat(opened.payBefore()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(first.paid()).isFalse();
        assertThat(again.tradeOrderId()).isEqualTo(first.tradeOrderId());
    }

    @Test
    void aVisitFeeIsOnlyDueAfterThatVisitIsAccepted() {
        UUID orderId = flow.order(ServiceOrderStatus.IN_PROGRESS, 0L, List.of(2_170L, 2_170L));
        flow.payNode(orderId, 1);

        assertThatThrownBy(() -> service.payNode(customer(), orderId, 2, "r"))
                .isInstanceOf(PaymentNotDueException.class);

        flow.acceptVisit(orderId, 1);
        service.payNode(customer(), orderId, 2, "r");

        assertThat(trade.opened).singleElement().satisfies(opened -> {
            assertThat(opened.amountMinor()).isEqualTo(2_170L);
            assertThat(opened.nodeLabel()).isEqualTo("第 1/2 次服务费");
        });
        assertThatThrownBy(() -> service.payNode(customer(), orderId, 3, "r"))
                .isInstanceOf(PaymentNotDueException.class);
    }

    @Test
    void aSingleVisitOrderCallsItsSecondNodeTheFeeAfterAcceptance() {
        assertThat(InspectionPaymentService.nodeLabel(
                new OrderPaymentNode(2, PaymentNodeType.PER_VISIT, 1, UUID.randomUUID(), 4_200L, null), 1))
                .isEqualTo("验收后付款 70%");
    }

    @Test
    void anotherCustomerCannotPayAndAnUnknownNodeIsNotFound() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        CurrentSubject stranger = new CurrentSubject(UUID.randomUUID(), Set.of("CUSTOMER"), UUID.randomUUID(),
                Set.of("inspection.plan.confirm.own"), 0);

        assertThatThrownBy(() -> service.payNode(stranger, orderId, 1, "r"))
                .isInstanceOf(PlanResourceNotFoundException.class);
        assertThatThrownBy(() -> service.payNode(customer(), orderId, 9, "r"))
                .isInstanceOf(PlanResourceNotFoundException.class);
        assertThat(trade.opened).isEmpty();
    }

    @Test
    void anExpiredOpenPaymentIsClosedFirstAndANewOneIsOpened() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        PaymentLaunchView first = service.payNode(customer(), orderId, 1, "r");
        flow.expireAttempts(NOW.minusSeconds(1));

        PaymentLaunchView second = service.payNode(customer(), orderId, 1, "r");

        assertThat(trade.cancelled).containsExactly(first.tradeOrderId());
        assertThat(second.tradeOrderId()).isNotEqualTo(first.tradeOrderId());
        assertThat(trade.opened).hasSize(2);
    }

    // ---- 付款回调 ----

    @Test
    void theDownPaymentCallbackPaysTheNodeAndStartsArrangingOnce() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        PaymentLaunchView launch = service.payNode(customer(), orderId, 1, "r");
        UUID attemptId = trade.opened.get(0).attemptId();

        assertThat(service.onPaymentConfirmed(launch.tradeOrderId(), attemptId)).isTrue();
        // 渠道重发通知：同一张单再回调一次，不重复记账、不重复推进。
        assertThat(service.onPaymentConfirmed(launch.tradeOrderId(), attemptId)).isTrue();

        StoredServiceOrder order = flow.orders.get(orderId);
        assertThat(order.status()).isEqualTo(ServiceOrderStatus.ARRANGING);
        assertThat(order.paymentNodes().get(0).paidAt()).isEqualTo(NOW);
        assertThat(flow.orderMoves).containsExactly("AWAITING_PAYMENT>ARRANGING");
        PaymentLaunchView paid = service.payNode(customer(), orderId, 1, "r");
        assertThat(paid.paid()).isTrue();
        assertThat(paid.tradeOrderId()).isEqualTo(launch.tradeOrderId());
        assertThat(trade.opened).hasSize(1);
    }

    @Test
    void aPaymentThatDoesNotMatchItsAttemptIsRefused() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        service.payNode(customer(), orderId, 1, "r");
        UUID attemptId = trade.opened.get(0).attemptId();

        assertThat(service.onPaymentConfirmed(UUID.randomUUID(), attemptId)).isFalse();
        assertThat(service.onPaymentConfirmed(UUID.randomUUID(), UUID.randomUUID())).isFalse();
        assertThat(flow.orders.get(orderId).paymentNodes().get(0).paidAt()).isNull();
    }

    @Test
    void aPaymentThatClosedAndArrivesLateStillPaysTheNodeIfNothingElseWasOpened() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        PaymentLaunchView launch = service.payNode(customer(), orderId, 1, "r");
        UUID attemptId = trade.opened.get(0).attemptId();
        service.onPaymentClosed(launch.tradeOrderId(), attemptId);

        assertThat(service.onPaymentConfirmed(launch.tradeOrderId(), attemptId)).isTrue();
        assertThat(flow.orders.get(orderId).status()).isEqualTo(ServiceOrderStatus.ARRANGING);
    }

    @Test
    void aLatePaymentIsRefundedWhenANewPaymentWasOpenedOrTheNodeWasPaid() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        PaymentLaunchView first = service.payNode(customer(), orderId, 1, "r");
        UUID firstAttempt = trade.opened.get(0).attemptId();
        service.onPaymentClosed(first.tradeOrderId(), firstAttempt);
        PaymentLaunchView second = service.payNode(customer(), orderId, 1, "r");
        UUID secondAttempt = trade.opened.get(1).attemptId();

        // 新的那张还没付：旧的晚到账，退回去。
        assertThat(service.onPaymentConfirmed(first.tradeOrderId(), firstAttempt)).isFalse();
        // 新的付了；旧的再到账也退。
        assertThat(service.onPaymentConfirmed(second.tradeOrderId(), secondAttempt)).isTrue();
        assertThat(service.onPaymentConfirmed(first.tradeOrderId(), firstAttempt)).isFalse();
        assertThat(flow.orders.get(orderId).paymentNodes().get(0).paidAt()).isEqualTo(NOW);
    }

    @Test
    void aPaymentForACancelledOrderIsRefunded() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        PaymentLaunchView launch = service.payNode(customer(), orderId, 1, "r");
        UUID attemptId = trade.opened.get(0).attemptId();
        trade.closeOnCancel = false;
        service.cancel(customer(), orderId, flow.orders.get(orderId).version(), null, "c");

        assertThat(service.onPaymentConfirmed(launch.tradeOrderId(), attemptId)).isFalse();
    }

    // ---- 取消 ----

    @Test
    void cancellingBeforeTheDownPaymentClosesThePaymentAndChargesNothing() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        PaymentLaunchView launch = service.payNode(customer(), orderId, 1, "r");

        ServiceOrderView cancelled = service.cancel(customer(), orderId, flow.orders.get(orderId).version(),
                " 计划变了 ", "c");

        assertThat(trade.cancelled).containsExactly(launch.tradeOrderId());
        assertThat(trade.refunds).isEmpty();
        assertThat(cancelled.status()).isEqualTo(ServiceOrderStatus.CANCELLED);
        assertThat(cancelled.extras().cancellation().reason()).isEqualTo("计划变了");
        assertThat(cancelled.paymentNodes()).extracting(OrderPaymentNodeView::payable).containsOnly(false);
    }

    @Test
    void cancellingAfterTheDownPaymentRefundsItInFullOnce() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        PaymentLaunchView launch = service.payNode(customer(), orderId, 1, "r");
        service.onPaymentConfirmed(launch.tradeOrderId(), trade.opened.get(0).attemptId());
        int version = flow.orders.get(orderId).version();

        ServiceOrderView cancelled = service.cancel(customer(), orderId, version, null, "c");
        ServiceOrderView again = service.cancel(customer(), orderId, version, null, "c2");

        assertThat(trade.refunds).singleElement().satisfies(refund -> {
            assertThat(refund.tradeOrderId()).isEqualTo(launch.tradeOrderId());
            assertThat(refund.amountMinor()).isEqualTo(900L);
            assertThat(refund.reason()).isEqualTo(InspectionPaymentService.CANCEL_REFUND_REASON);
            assertThat(refund.key()).isEqualTo(InspectionPaymentService.refundKey(orderId));
        });
        // 先锁交易订单，再锁服务单。
        assertThat(trade.locked).containsExactly(launch.tradeOrderId());
        assertThat(cancelled.status()).isEqualTo(ServiceOrderStatus.CANCELLED);
        assertThat(cancelled.paymentNodes().get(0).refund()).isNotNull();
        assertThat(again.status()).isEqualTo(ServiceOrderStatus.CANCELLED);
    }

    @Test
    void afterTheFirstVisitStartsTheOrderCannotBeCancelledOnline() {
        UUID orderId = flow.order(ServiceOrderStatus.IN_PROGRESS, 0L, List.of(2_100L));
        flow.startVisit(orderId, 1);

        assertThatThrownBy(() -> service.cancel(customer(), orderId, flow.orders.get(orderId).version(), null, "c"))
                .isInstanceOf(OrderNotCancellableException.class);
        assertThat(trade.refunds).isEmpty();
    }

    @Test
    void cancellingWithAStaleVersionIsAConflict() {
        UUID orderId = flow.order(ServiceOrderStatus.ARRANGING, 0L, List.of(2_100L));

        assertThatThrownBy(() -> service.cancel(customer(), orderId, 99, null, "c"))
                .isInstanceOf(PlanVersionConflictException.class);
        assertThatThrownBy(() -> service.cancel(customer(), orderId, 0, "x".repeat(201), "c"))
                .isInstanceOf(PlanValidationException.class);
    }

    @Test
    void aCancelledOrderOffersNoPayment() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        service.cancel(customer(), orderId, flow.orders.get(orderId).version(), null, "c");

        assertThatThrownBy(() -> service.payNode(customer(), orderId, 1, "r"))
                .isInstanceOf(PaymentNotDueException.class);
    }

    // 首付到账之前服务商不能安排、排期、推进；到账后可以。
    @Test
    void theProviderCannotArrangeBeforeTheDownPaymentArrives() {
        UUID orderId = flow.order(ServiceOrderStatus.AWAITING_PAYMENT, 900L, List.of(2_100L));
        OrderApplicationService orders = new OrderApplicationService(flow, Clock.fixed(NOW, ZoneOffset.UTC));
        CurrentSubject provider = new CurrentSubject(UUID.randomUUID(), Set.of("PROVIDER_OPERATOR"), PROVIDER,
                Set.of("inspection.order.arrange.assigned", "inspection.order.progress.assigned"), 0);
        PutArrangementCommand arrangement = new PutArrangementCommand(List.of(
                new AssignmentInput(AssignmentType.TEAM_MEMBER, UUID.randomUUID(), "王工", "项目负责人", null),
                new AssignmentInput(AssignmentType.DEVICE, UUID.randomUUID(), "行业无人机", null, null)));
        StoredServiceVisit visit = flow.visit(orderId, 1);

        assertThatThrownBy(() -> orders.arrange(provider, orderId, UUID.randomUUID(), 0, arrangement, "a"))
                .isInstanceOf(OrderStateInvalidException.class);
        assertThatThrownBy(() -> orders.schedule(provider, orderId, visit.id(), UUID.randomUUID(), visit.version(),
                new PutVisitScheduleCommand(NOW.plus(Duration.ofDays(2))), "s"))
                .isInstanceOf(OrderStateInvalidException.class);
        assertThatThrownBy(() -> orders.appendProgress(provider, orderId, visit.id(), UUID.randomUUID(),
                visit.version(), new AppendProgressCommand(OrderProgressCode.SERVICE_STARTED, NOW, "开工"), "p"))
                .isInstanceOf(OrderStateInvalidException.class);
    }

    static CurrentSubject customer() {
        return new CurrentSubject(CUSTOMER, Set.of("CUSTOMER"), UUID.randomUUID(),
                Set.of("inspection.plan.confirm.own", "inspection.acceptance.create.own"), 0);
    }

    static CurrentSubject provider() {
        return new CurrentSubject(UUID.randomUUID(), Set.of("PROVIDER_OPERATOR"), PROVIDER,
                Set.of("inspection.order.arrange.assigned"), 0);
    }

    static LocalDate day(int offset) {
        return LocalDate.ofInstant(NOW, PaymentRules.BEIJING).plusDays(offset);
    }
}
