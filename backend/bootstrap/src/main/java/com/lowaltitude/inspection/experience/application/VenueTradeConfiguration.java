package com.lowaltitude.inspection.experience.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.experience.application.VenueBookingException.Error;
import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.trade.application.TradeSourceListener;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 到馆预约接到统一交易模块（E10，docs/redesign/03-App实现任务计划.md §21.2）：体验模块的付款端口用交易模块的
 * 业务来源入口实现（订单来源 VENUE，收款方是场馆运营机构）；交易订单付款、关闭时转给预约服务。两个模块互不依赖。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class VenueTradeConfiguration {

    static final String SOURCE = "VENUE";

    @Bean
    VenuePaymentPort venuePaymentPort(TradeSourceOrders orders) {
        return new TradeBackedVenuePayments(orders);
    }

    @Bean
    TradeSourceListener venueTradeSourceListener(VenueBookingService bookings) {
        return new TradeSourceListener() {
            @Override
            public String source() {
                return SOURCE;
            }

            @Override
            public boolean onPaid(UUID orderId, UUID sourceRef) {
                return bookings.onPaymentConfirmed(orderId, sourceRef);
            }

            @Override
            public void onClosed(UUID orderId, UUID sourceRef) {
                bookings.onPaymentClosed(orderId, sourceRef);
            }
        };
    }

    /** 交易模块的拒绝换成预约自己的错误，页面拿到的是 VENUE_ 错误码。 */
    static final class TradeBackedVenuePayments implements VenuePaymentPort {

        private final TradeSourceOrders orders;

        TradeBackedVenuePayments(TradeSourceOrders orders) {
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
                        request.bookingId(),
                        request.accountId(),
                        request.operatorId(),
                        request.cityCode(),
                        List.of(new TradeSourceOrders.Line(
                                request.projectId(),
                                request.sessionId(),
                                request.projectName(),
                                request.sessionName(),
                                request.coverUrl(),
                                request.unitAmountMinor(),
                                request.people())),
                        request.payBefore()));
                return new OpenedPayment(opened.orderId(), opened.payBefore());
            } catch (TradeException exception) {
                if (exception.error() == TradeException.Error.MERCHANT_PAYMENT_UNAVAILABLE) {
                    throw new VenueBookingException(Error.PAYMENT_UNAVAILABLE);
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
                    // 超过可退金额、次数或期限，订单不是已付款：不能在线退。
                    case REFUND_EXCEEDS_PAID, REFUND_LIMIT_REACHED, ORDER_STATE_INVALID ->
                            throw new VenueBookingException(Error.CANCEL_NOT_ALLOWED);
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
