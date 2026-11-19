package com.lowaltitude.inspection.activity.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.trade.application.TradeSourceListener;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 收费活动报名接到统一交易模块（E09，docs/redesign/03-App实现任务计划.md §20.2）：
 * 活动模块的付款端口用交易模块的业务来源入口实现；交易订单付款、关闭时转给活动模块。两个模块互不依赖。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ActivityTradeConfiguration {

    static final String SOURCE = "ACTIVITY";

    @Bean
    ActivityPaymentPort activityPaymentPort(TradeSourceOrders orders) {
        return new TradeBackedPayments(orders);
    }

    @Bean
    TradeSourceListener activityTradeSourceListener(ActivityEnrollmentService enrollments) {
        return new TradeSourceListener() {
            @Override
            public String source() {
                return SOURCE;
            }

            @Override
            public boolean onPaid(UUID orderId, UUID sourceRef) {
                return enrollments.onPaymentConfirmed(orderId, sourceRef);
            }

            @Override
            public void onClosed(UUID orderId, UUID sourceRef) {
                enrollments.onPaymentClosed(orderId, sourceRef);
            }
        };
    }

    /** 交易模块的拒绝换成活动模块自己的错误，页面拿到的是报名的错误码。 */
    static final class TradeBackedPayments implements ActivityPaymentPort {

        private final TradeSourceOrders orders;

        TradeBackedPayments(TradeSourceOrders orders) {
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
                        request.enrollmentId(),
                        request.accountId(),
                        request.organizerId(),
                        request.cityCode(),
                        List.of(new TradeSourceOrders.Line(
                                request.activityId(),
                                request.sessionId(),
                                request.activityTitle(),
                                request.sessionName(),
                                request.coverUrl(),
                                request.unitAmountMinor(),
                                request.people())),
                        request.payBefore()));
                return new OpenedPayment(opened.orderId(), opened.payBefore());
            } catch (TradeException exception) {
                if (exception.error() == TradeException.Error.MERCHANT_PAYMENT_UNAVAILABLE) {
                    throw new ActivityEnrollmentException(Error.PAYMENT_UNAVAILABLE);
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
                    // 超过可退金额、次数或期限，订单不是已付款：这几位不能在线退。
                    case REFUND_EXCEEDS_PAID, REFUND_LIMIT_REACHED, ORDER_STATE_INVALID ->
                            throw new ActivityEnrollmentException(Error.CANCEL_NOT_ALLOWED);
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
        public void cancelUnpaid(UUID accountId, UUID tradeOrderId) {
            orders.cancelUnpaid(accountId, tradeOrderId);
        }
    }
}
