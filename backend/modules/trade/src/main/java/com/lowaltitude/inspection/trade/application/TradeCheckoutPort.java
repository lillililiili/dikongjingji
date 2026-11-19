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

    record CheckoutRequest(
            String cityCode,
            List<UUID> cartItemIds,
            UUID offerId,
            Integer quantity,
            String deliveryMode,
            UUID addressId
    ) {
    }

    /**
     * deliveryMode 是这一单实际能用的方式，可能和请求的不同（请求的方式不可用时）；为 null 表示没有可用的方式。
     * address 只在快递时给出；快递却没有地址时为 null。
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
            long payableMinor
    ) {
    }

    record QuotedLine(
            UUID productId,
            UUID offerId,
            String productName,
            String skuName,
            String coverUrl,
            long unitAmountMinor,
            int quantity,
            long subtotalMinor
    ) {
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
