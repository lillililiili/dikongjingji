package com.lowaltitude.inspection.trade.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 对外的形状，字段与契约 contracts/openapi-trade.yaml 逐个对应。 */
public final class TradeViews {

    private TradeViews() {
    }

    public record TradeOrderView(
            UUID orderId,
            String orderNo,
            /** MALL 商城；ACTIVITY 收费活动报名（E09）。 */
            String source,
            /** 业务单 ID（收费报名时是报名单 ID）；商城订单为 null。 */
            UUID sourceRef,
            TradeOrderStatus status,
            TradeMerchantView merchant,
            /** 业务来源的订单是收费报名的场次等，不是商品：productId 是业务项目 ID（如活动 ID），offerId 是选项 ID（如场次 ID）。 */
            List<TradeOrderLineView> lines,
            /** 业务来源的订单不发货，为 null。 */
            TradeDeliveryView delivery,
            TradeAmountsView amounts,
            String buyerNote,
            Instant createdAt,
            Instant payBefore,
            Instant paidAt,
            Instant completedAt,
            Instant closedAt,
            /** CLOSED 时是 BUYER_CANCEL（买家取消）还是 TIMEOUT（超时关闭）；其他状态为 null。 */
            String closeReason,
            List<TradeRefundView> refunds,
            boolean cancellable,
            int version
    ) {
    }

    /** kind 是商城的商家类型；业务来源的订单（收费报名的主办机构）为 null。 */
    public record TradeMerchantView(UUID organizationId, String name, String kind) {
    }

    public record TradeMediaView(String url, String alt) {
    }

    public record TradeOrderLineView(
            UUID lineId,
            UUID productId,
            UUID offerId,
            String productName,
            String skuName,
            TradeMediaView coverImage,
            long unitAmountMinor,
            int quantity,
            long subtotalMinor
    ) {
    }

    /**
     * recipientPhone 是完整号码，只在商家接口、并且订单已付款之后给（发货要用），买家接口为 null。
     * addressLine 买家接口总是给；商家接口也只在订单已付款之后给，待付款、已关闭时为 null。
     */
    public record TradeDeliveryView(
            String mode,
            String recipientName,
            String phoneMasked,
            String recipientPhone,
            String addressLine,
            String pickupPoint,
            String pickupCode,
            String carrierName,
            String trackingNo,
            Instant shippedAt
    ) {
    }

    public record TradeAmountsView(
            long goodsMinor,
            long shippingMinor,
            long payableMinor,
            long paidMinor,
            long refundedMinor,
            String currency
    ) {
    }

    public record TradeRefundView(
            UUID refundId,
            long amountMinor,
            String reason,
            String status,
            Instant requestedAt,
            Instant succeededAt
    ) {
    }

    public record TradeOrderPageView(int page, int size, long total, boolean hasMore, List<TradeOrderView> items) {
    }

    public record MerchantAccountView(UUID organizationId, String subMchid, boolean active) {
    }

    /** created 为 false 表示同一个幂等键重放，返回的是第一次建的订单。 */
    public record SubmitResult(TradeOrderView order, boolean created) {
    }

    /** 发起支付的结果：App 拿 appCall 调起微信。expiresAt 是预支付有效期和订单付款截止时间里较早的那个。 */
    public record PaymentLaunchView(UUID paymentId, String channel, Instant expiresAt, WechatAppCallView appCall) {
    }

    /** 某一天的对账结果（E06）。 */
    public record ReconciliationView(LocalDate date, Instant importedAt, int matchedCount,
                                     List<ReconciliationDifferenceView> differences) {
    }

    public record ReconciliationDifferenceView(String kind, String subMchid, String outTradeNo, String outRefundNo,
                                               Long platformAmountMinor, Long channelAmountMinor) {
    }

    /** 跑一次对账的结果：这一天要对的子商户数，这次导入成功、失败、跳过（之前已成功）的数，发现的差异数。 */
    public record ReconciliationRunView(LocalDate date, int merchants, int imported, int failed, int skipped,
                                        int differences) {
    }

    public record WechatAppCallView(String appId, String partnerId, String prepayId, String packageValue,
                                    String nonceStr, String timeStamp, String sign) {
    }
}
