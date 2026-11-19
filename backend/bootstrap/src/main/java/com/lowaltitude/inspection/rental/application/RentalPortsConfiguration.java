package com.lowaltitude.inspection.rental.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.account.application.AccountAddressService;
import com.lowaltitude.inspection.account.application.AddressNotFoundException;
import com.lowaltitude.inspection.mall.application.MallRentalOffers;
import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.trade.application.TradeSourceListener;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 租赁模块的端口接到别的模块（F3，§24.2）：商城的租赁供给、账号的收货地址、统一交易模块（订单来源 RENTAL，收款方是出租方）；
 * 交易订单付款、关闭时转给租赁服务。几个模块互不依赖。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class RentalPortsConfiguration {

    static final String SOURCE = "RENTAL";

    @Bean
    RentalPorts.Offers rentalOffers(MallRentalOffers offers) {
        return offerId -> offers.find(offerId).map(offer -> new RentalPorts.Offer(
                offer.offerId(), offer.productId(), offer.productName(), offer.skuName(), offer.coverUrl(),
                offer.merchantId(), offer.merchantName(), offer.cityCode(), offer.referenceAmountMinor(),
                offer.priceUnit(), offer.rentable()));
    }

    @Bean
    RentalPorts.Addresses rentalAddresses(AccountAddressService addresses) {
        return (accountId, addressId) -> {
            if (addressId == null) {
                return Optional.empty();
            }
            try {
                return addresses.findForUse(accountId, addressId).map(address -> new RentalPorts.Address(
                        address.addressId(),
                        address.recipientName(),
                        address.phone(),
                        address.region().provinceName(),
                        address.region().cityCode(),
                        address.region().cityName(),
                        address.detail()));
            } catch (AddressNotFoundException notOwned) {
                // 地址不存在或不是这个账号的：和“没有这条地址”一样。
                return Optional.empty();
            }
        };
    }

    @Bean
    RentalPorts.Payments rentalPayments(TradeSourceOrders orders) {
        return new TradeBackedRentalPayments(orders);
    }

    @Bean
    TradeSourceListener rentalTradeSourceListener(RentalService rentals) {
        return new TradeSourceListener() {
            @Override
            public String source() {
                return SOURCE;
            }

            @Override
            public boolean onPaid(UUID orderId, UUID sourceRef) {
                return rentals.onPaymentConfirmed(orderId, sourceRef);
            }

            @Override
            public void onClosed(UUID orderId, UUID sourceRef) {
                rentals.onPaymentClosed(orderId, sourceRef);
            }
        };
    }

    /** 交易模块的拒绝换成租赁自己的错误，页面拿到的是 RENTAL_ 错误码。 */
    static final class TradeBackedRentalPayments implements RentalPorts.Payments {

        private final TradeSourceOrders orders;

        TradeBackedRentalPayments(TradeSourceOrders orders) {
            this.orders = orders;
        }

        @Override
        public boolean merchantCanCollect(UUID organizationId) {
            return orders.merchantCanCollect(organizationId);
        }

        @Override
        public RentalPorts.OpenedPayment open(RentalPorts.OpenPayment request) {
            try {
                TradeSourceOrders.OpenedOrder opened = orders.open(new TradeSourceOrders.OpenCommand(
                        SOURCE,
                        request.rentalOrderId(),
                        request.accountId(),
                        request.merchantId(),
                        request.cityCode(),
                        request.lines().stream()
                                .map(line -> new TradeSourceOrders.Line(line.itemId(), line.optionId(),
                                        line.itemName(), line.optionName(), line.coverUrl(), line.unitAmountMinor(),
                                        line.quantity()))
                                .toList(),
                        request.payBefore()));
                return new RentalPorts.OpenedPayment(opened.orderId(), opened.payBefore());
            } catch (TradeException exception) {
                if (exception.error() == TradeException.Error.MERCHANT_PAYMENT_UNAVAILABLE) {
                    throw new RentalException(RentalException.Error.PAYMENT_UNAVAILABLE);
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
                    case REFUND_EXCEEDS_PAID, REFUND_LIMIT_REACHED, ORDER_STATE_INVALID ->
                            throw new RentalException(RentalException.Error.REFUND_NOT_ALLOWED);
                    default -> throw exception;
                }
            }
        }

        @Override
        public Map<UUID, RentalPorts.RefundProgress> refundStates(Collection<UUID> refundIds) {
            return orders.refundStates(refundIds).stream()
                    .collect(Collectors.toMap(
                            TradeSourceOrders.RefundState::refundId,
                            state -> new RentalPorts.RefundProgress(state.status(), state.amountMinor())));
        }
    }
}
