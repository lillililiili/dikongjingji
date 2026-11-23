package com.lowaltitude.inspection.application.serviceflow;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.trade.application.TradeOrderStatus;
import com.lowaltitude.inspection.trade.application.TradeSourceListener;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 巡检付款节点接到统一交易模块（S4-1，docs/redesign/08-后续开发计划.md §5 D9）：巡检模块的付款端口用交易模块的
 * 业务来源入口实现（订单来源 INSPECTION，收款方是巡检服务商）；交易订单付款、关闭时转给巡检付款服务。两个模块互不依赖。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class InspectionTradeConfiguration {

    static final String SOURCE = "INSPECTION";

    @Bean
    InspectionPaymentPort inspectionPaymentPort(TradeSourceOrders orders) {
        return new TradeBackedInspectionPayments(orders);
    }

    @Bean
    TradeSourceListener inspectionTradeSourceListener(InspectionPaymentService payments) {
        return new TradeSourceListener() {
            @Override
            public String source() {
                return SOURCE;
            }

            @Override
            public boolean onPaid(UUID orderId, UUID sourceRef) {
                return payments.onPaymentConfirmed(orderId, sourceRef);
            }

            @Override
            public void onClosed(UUID orderId, UUID sourceRef) {
                payments.onPaymentClosed(orderId, sourceRef);
            }
        };
    }

    /** 交易模块的拒绝换成巡检自己的错误，页面拿到的是 INS_ 错误码。 */
    static final class TradeBackedInspectionPayments implements InspectionPaymentPort {

        private final TradeSourceOrders orders;

        TradeBackedInspectionPayments(TradeSourceOrders orders) {
            this.orders = orders;
        }

        @Override
        public boolean merchantCanCollect(UUID organizationId) {
            return orders.merchantCanCollect(organizationId);
        }

        @Override
        public OpenedPayment open(OpenPayment request) {
            try {
                TradeSourceOrders.OpenedOrder opened = orders.open(new TradeSourceOrders.OpenCommand(
                        SOURCE,
                        request.attemptId(),
                        request.customerId(),
                        request.providerId(),
                        request.cityCode(),
                        List.of(new TradeSourceOrders.Line(
                                request.serviceOrderId(),
                                request.attemptId(),
                                request.orderTitle(),
                                request.nodeLabel(),
                                request.coverUrl(),
                                request.amountMinor(),
                                1)),
                        request.payBefore()));
                return new OpenedPayment(opened.orderId(), opened.payBefore());
            } catch (TradeException exception) {
                if (exception.error() == TradeException.Error.MERCHANT_PAYMENT_UNAVAILABLE) {
                    throw new ProviderPaymentUnavailableException();
                }
                throw exception;
            }
        }

        @Override
        public void lockOrder(UUID tradeOrderId) {
            orders.lockOrder(tradeOrderId);
        }

        @Override
        public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                                  UUID actorId) {
            try {
                return orders.requestRefund(tradeOrderId, amountMinor, reason, idempotencyKey, actorId);
            } catch (TradeException exception) {
                switch (exception.error()) {
                    // 超过可退金额、次数或期限，订单不是已付款：不能在线退，走客服协商。
                    case REFUND_EXCEEDS_PAID, REFUND_LIMIT_REACHED, ORDER_STATE_INVALID ->
                            throw new OrderNotCancellableException();
                    default -> throw exception;
                }
            }
        }

        @Override
        public Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds) {
            return orders.refundStates(refundIds).stream()
                    .collect(Collectors.toMap(
                            TradeSourceOrders.RefundState::refundId,
                            state -> new RefundProgress(state.status(), state.amountMinor())));
        }

        @Override
        public boolean cancelUnpaid(UUID accountId, UUID tradeOrderId) {
            return orders.cancelUnpaid(accountId, tradeOrderId) != TradeOrderStatus.CLOSED;
        }
    }
}
