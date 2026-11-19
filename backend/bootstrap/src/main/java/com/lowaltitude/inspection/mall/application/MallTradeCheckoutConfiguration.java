package com.lowaltitude.inspection.mall.application;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.account.application.AccountAddressService;
import com.lowaltitude.inspection.account.application.AddressView;
import com.lowaltitude.inspection.trade.application.TradeCheckoutPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 交易模块的报价来自商城的确认订单预览和账号的收货地址：提交订单时和页面看到的确认订单用同一套算法，
 * 不另写一套价格规则。放在商城的包里，才能读到预览的内部结构。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class MallTradeCheckoutConfiguration {

    @Bean
    TradeCheckoutPort mallTradeCheckout(MallCartService carts, AccountAddressService addresses,
                                        MallInquiryService inquiries) {
        return new TradeCheckoutPort() {
            @Override
            public CheckoutQuote quote(UUID accountId, CheckoutRequest request) {
                CheckoutPreview preview = carts.preview(
                        accountId,
                        request.cityCode(),
                        request.cartItemIds(),
                        request.offerId(),
                        request.quantity(),
                        request.deliveryMode(),
                        request.inquiryQuoteId());
                // 按询价报价下单（F4）：带上用这份报价下过的上一张订单，交易模块据此拒绝第二张没关闭的订单。
                UUID previousOrderId = request.inquiryQuoteId() == null
                        ? null
                        : inquiries.orderQuote(accountId, request.inquiryQuoteId()).lastOrderId();
                String mode = preview.deliveryMode() == null ? null : preview.deliveryMode().name();
                AddressSnapshot address = null;
                if (preview.deliveryMode() == DeliveryMode.EXPRESS) {
                    address = addresses.findForUse(accountId, request.addressId())
                            .map(MallTradeCheckoutConfiguration::snapshot)
                            .orElse(null);
                }
                String pickupPoint = preview.deliveryOptions().stream()
                        .filter(option -> option.mode() == DeliveryMode.PICKUP)
                        .map(DeliveryOptionView::note)
                        .findFirst()
                        .orElse(null);
                return new CheckoutQuote(
                        preview.merchant().organizationId(),
                        preview.merchant().name(),
                        preview.merchant().kind().name(),
                        preview.lines().stream()
                                .map(line -> new QuotedLine(
                                        line.productId(),
                                        line.offerId(),
                                        line.productName(),
                                        line.skuName(),
                                        line.coverImage() == null ? null : line.coverImage().url(),
                                        line.unitAmountMinor(),
                                        line.quantity(),
                                        line.subtotalMinor()))
                                .toList(),
                        mode,
                        pickupPoint,
                        address,
                        preview.amounts().goodsMinor(),
                        preview.amounts().shippingMinor(),
                        preview.amounts().payableMinor(),
                        previousOrderId);
            }

            @Override
            public void removeCartLines(UUID accountId, List<UUID> cartItemIds) {
                carts.remove(accountId, cartItemIds);
            }

            @Override
            public void orderPlaced(UUID accountId, CheckoutRequest request, UUID orderId) {
                if (request.inquiryQuoteId() != null) {
                    inquiries.recordOrder(accountId, request.inquiryQuoteId(), orderId);
                }
            }
        };
    }

    private static TradeCheckoutPort.AddressSnapshot snapshot(AddressView address) {
        return new TradeCheckoutPort.AddressSnapshot(
                address.recipientName(),
                address.phone(),
                address.region().provinceName(),
                address.region().cityName(),
                address.detail());
    }
}
