package com.lowaltitude.inspection.serviceorder.application;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.bootstrap.infrastructure.LocalServiceOrderFileStore;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.trade.application.TradeOrderStatus;
import com.lowaltitude.inspection.trade.application.TradeSourceListener;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 服务订单模块的端口接到别的模块（S4-3，§28）：统一交易模块（订单来源 PRO_SERVICE，收款方是服务商）、
 * 身份模块（账号显示名、服务商资质是否审核过）、交付文件存储（配置了 serviceorder.files.local-dir 时用本机目录，
 * 只在 application-dev.yml 里有；否则一律不可用，上传、下载返回 503）。交易订单付款、关闭时转给服务订单。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ServiceOrderPortsConfiguration {

    static final String SOURCE = "PRO_SERVICE";

    @Bean
    ServiceOrderPorts.Payments serviceOrderPayments(TradeSourceOrders orders) {
        return new TradeBackedServiceOrderPayments(orders);
    }

    @Bean
    ServiceOrderPorts.People serviceOrderPeople(IdentityProfilePort profiles, IdentityOrganizationPort organizations) {
        return new ServiceOrderPorts.People() {
            @Override
            public Optional<String> displayName(UUID accountId) {
                return accountId == null ? Optional.empty()
                        : profiles.findProfile(accountId).map(IdentityProfile::displayName);
            }

            @Override
            public Set<UUID> qualificationReviewed(Collection<UUID> organizationIds) {
                return organizationIds.isEmpty() ? Set.of() : organizations.qualificationReviewed(organizationIds);
            }
        };
    }

    @Bean
    ServiceOrderPorts.Files serviceOrderFiles(@Value("${serviceorder.files.local-dir:}") String localDirectory) {
        return localDirectory == null || localDirectory.isBlank()
                ? new LocalServiceOrderFileStore.Unavailable()
                : new LocalServiceOrderFileStore(Path.of(localDirectory));
    }

    @Bean
    TradeSourceListener serviceOrderTradeSourceListener(ServiceOrderService orders) {
        return new TradeSourceListener() {
            @Override
            public String source() {
                return SOURCE;
            }

            @Override
            public boolean onPaid(UUID orderId, UUID sourceRef) {
                return orders.onPaymentConfirmed(orderId, sourceRef);
            }

            @Override
            public void onClosed(UUID orderId, UUID sourceRef) {
                orders.onPaymentClosed(orderId, sourceRef);
            }
        };
    }

    /** 交易模块的拒绝换成服务订单自己的错误，页面拿到的是 SERVICE_ORDER_ 错误码。 */
    static final class TradeBackedServiceOrderPayments implements ServiceOrderPorts.Payments {

        private final TradeSourceOrders orders;

        TradeBackedServiceOrderPayments(TradeSourceOrders orders) {
            this.orders = orders;
        }

        @Override
        public boolean merchantCanCollect(UUID organizationId) {
            return orders.merchantCanCollect(organizationId);
        }

        @Override
        public ServiceOrderPorts.OpenedPayment open(ServiceOrderPorts.OpenPayment request) {
            try {
                TradeSourceOrders.OpenedOrder opened = orders.open(new TradeSourceOrders.OpenCommand(
                        SOURCE,
                        request.attemptId(),
                        request.accountId(),
                        request.providerId(),
                        request.cityCode(),
                        List.of(new TradeSourceOrders.Line(request.serviceOrderId(), request.attemptId(),
                                request.itemName(), request.optionName(), null, request.amountMinor(), 1)),
                        request.payBefore()));
                return new ServiceOrderPorts.OpenedPayment(opened.orderId(), opened.payBefore());
            } catch (TradeException exception) {
                if (exception.error() == TradeException.Error.MERCHANT_PAYMENT_UNAVAILABLE) {
                    throw new ServiceOrderException(ServiceOrderException.Error.PAYMENT_UNAVAILABLE);
                }
                throw exception;
            }
        }

        @Override
        public void lockOrder(UUID tradeOrderId) {
            orders.lockOrder(tradeOrderId);
        }

        @Override
        public boolean cancelUnpaid(UUID buyerId, UUID tradeOrderId) {
            return orders.cancelUnpaid(buyerId, tradeOrderId) != TradeOrderStatus.CLOSED;
        }

        @Override
        public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                                  UUID actorId) {
            try {
                return orders.requestRefund(tradeOrderId, amountMinor, reason, idempotencyKey, actorId);
            } catch (TradeException exception) {
                switch (exception.error()) {
                    case REFUND_EXCEEDS_PAID, REFUND_LIMIT_REACHED, ORDER_STATE_INVALID ->
                            throw new ServiceOrderException(ServiceOrderException.Error.REFUND_NOT_ALLOWED);
                    default -> throw exception;
                }
            }
        }

        @Override
        public Map<UUID, String> refundStates(Collection<UUID> refundIds) {
            return orders.refundStates(refundIds).stream()
                    .collect(Collectors.toMap(TradeSourceOrders.RefundState::refundId,
                            TradeSourceOrders.RefundState::status));
        }
    }
}
