package com.lowaltitude.inspection.trade.application;

import java.util.List;
import java.util.UUID;

/**
 * 提交订单时向业务模块要“这一单现在值多少钱”。一期只有商城：启动模块用确认订单预览和收货地址实现它，
 * 交易模块不直接读商城和地址的表。报价必须是服务端此刻重新算出来的，不能用页面传来的价格。
 */
public interface TradeCheckoutPort {

    /** 同一个商家的一单；商品失效、跨商家、地址不存在等由实现方按各自的业务异常抛出。 */
    CheckoutQuote quote(UUID accountId, CheckoutRequest request);

    /** 下单成功后，把来自购物车的几行删掉。 */
    void removeCartLines(UUID accountId, List<UUID> cartItemIds);

    /**
     * 订单建好之后（同一个事务里）告诉业务模块：按询价报价下的单（F4），商城在报价上记下这张订单。
     * 别的来源什么都不做。
     */
    default void orderPlaced(UUID accountId, CheckoutRequest request, UUID orderId) {
    }

    /**
     * 三种来源只有一种：购物车行（cartItemIds）、单个供给（offerId + quantity）、选定的询价报价（inquiryQuoteId，F4）。
     */
    record CheckoutRequest(
            String cityCode,
            List<UUID> cartItemIds,
            UUID offerId,
            Integer quantity,
            String deliveryMode,
            UUID addressId,
            UUID inquiryQuoteId
    ) {
        public CheckoutRequest(String cityCode, List<UUID> cartItemIds, UUID offerId, Integer quantity,
                               String deliveryMode, UUID addressId) {
            this(cityCode, cartItemIds, offerId, quantity, deliveryMode, addressId, null);
        }
    }

    /**
     * deliveryMode 是这一单实际能用的方式，可能和请求的不同（请求的方式不可用时）；为 null 表示没有可用的方式。
     * address 只在快递时给出；快递却没有地址时为 null。
     * previousOrderId：按询价报价下单时，用这份报价下过的上一张订单（F4）；交易模块据此拒绝第二张没关闭的订单。
     */
    record CheckoutQuote(
            UUID merchantId,
            String merchantName,
            String merchantKind,
            List<QuotedLine> lines,
            String deliveryMode,
            String pickupPoint,
            AddressSnapshot address,
            long goodsMinor,
            long shippingMinor,
            long payableMinor,
            UUID previousOrderId,
            /** 0.6.0：交易主体快照（发货仓、开票方、售后责任方），没有时为 null。 */
            String fulfilledFrom,
            String invoiceIssuer,
            String aftersaleParty
    ) {
        public CheckoutQuote(UUID merchantId, String merchantName, String merchantKind, List<QuotedLine> lines,
                             String deliveryMode, String pickupPoint, AddressSnapshot address, long goodsMinor,
                             long shippingMinor, long payableMinor) {
            this(merchantId, merchantName, merchantKind, lines, deliveryMode, pickupPoint, address, goodsMinor,
                    shippingMinor, payableMinor, null);
        }

        public CheckoutQuote(UUID merchantId, String merchantName, String merchantKind, List<QuotedLine> lines,
                             String deliveryMode, String pickupPoint, AddressSnapshot address, long goodsMinor,
                             long shippingMinor, long payableMinor, UUID previousOrderId) {
            this(merchantId, merchantName, merchantKind, lines, deliveryMode, pickupPoint, address, goodsMinor,
                    shippingMinor, payableMinor, previousOrderId, null, null, null);
        }
    }

    record QuotedLine(
            UUID productId,
            UUID offerId,
            String productName,
            String skuName,
            String coverUrl,
            long unitAmountMinor,
            int quantity,
            long subtotalMinor,
            /** 0.6.0：这件商品是否支持七天无理由（商品可以标“不支持”）。 */
            boolean noReasonReturn
    ) {
        public QuotedLine(UUID productId, UUID offerId, String productName, String skuName, String coverUrl,
                          long unitAmountMinor, int quantity, long subtotalMinor) {
            this(productId, offerId, productName, skuName, coverUrl, unitAmountMinor, quantity, subtotalMinor, true);
        }
    }

    record AddressSnapshot(
            String recipientName,
            String phone,
            String provinceName,
            String cityName,
            String detail
    ) {
    }
}
