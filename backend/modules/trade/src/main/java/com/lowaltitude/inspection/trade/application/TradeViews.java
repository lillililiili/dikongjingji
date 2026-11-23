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
            int version,
            /** 0.7.0：下单时的交易主体快照（发货仓、开票方、售后责任方）；业务来源的订单三项都为 null。 */
            TradeOrderPartiesView parties,
            /** 0.7.0：付款成功的支付单在微信的交易号（transaction_id），验签后的回调或查单才有；没付款时为 null。只用来显示。 */
            String channelTransactionId,
            /** 0.7.0：收费报名订单的参与人姓名（按报名顺序，顿号连接），只用来显示；别的订单为 null。 */
            String participantNames,
            /** 0.7.0：这张单的售后（只有商城订单有意义）。 */
            TradeOrderAftersaleView aftersale
    ) {
        /** 0.5.0 及以前的字段（测试和老调用方用）：新字段都取空。 */
        public TradeOrderView(UUID orderId, String orderNo, String source, UUID sourceRef, TradeOrderStatus status,
                              TradeMerchantView merchant, List<TradeOrderLineView> lines, TradeDeliveryView delivery,
                              TradeAmountsView amounts, String buyerNote, Instant createdAt, Instant payBefore,
                              Instant paidAt, Instant completedAt, Instant closedAt, String closeReason,
                              List<TradeRefundView> refunds, boolean cancellable, int version) {
            this(orderId, orderNo, source, sourceRef, status, merchant, lines, delivery, amounts, buyerNote, createdAt,
                    payBefore, paidAt, completedAt, closedAt, closeReason, refunds, cancellable, version,
                    TradeOrderPartiesView.NONE, null, null, TradeOrderAftersaleView.NONE);
        }
    }

    /**
     * 交易主体（0.7.0，画板 OrderGoods）：fulfilledFrom 从哪里发货（商城供给的发货仓，几行不同时用“；”连接）；
     * invoiceIssuer 开票方；aftersaleParty 售后责任方。都是下单时的快照，没有时为 null（页面不显示这一行）。
     */
    public record TradeOrderPartiesView(String fulfilledFrom, String invoiceIssuer, String aftersaleParty) {
        public static final TradeOrderPartiesView NONE = new TradeOrderPartiesView(null, null, null);
    }

    /**
     * 订单上的售后入口（0.7.0）：latestAftersaleId、latestStatus 是最近一张售后单（没有时为 null）；
     * applicable 是现在能不能申请（服务端按订单状态、收货时间、有没有没结束的售后单、还能退的钱算；
     * 具体能选哪类、哪个原因看售后申请页的 eligibility）。
     */
    public record TradeOrderAftersaleView(UUID latestAftersaleId, String latestStatus, boolean applicable) {
        public static final TradeOrderAftersaleView NONE = new TradeOrderAftersaleView(null, null, false);
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

    /**
     * 支付通道自检（S2-1）的结果。mode：WECHAT 真实微信，SIMULATED 指向本机模拟微信，DISABLED 没有开启。
     * passed 为 true 表示没有 FAIL 项（WARN、SKIPPED 不算失败）。
     */
    public record ChannelCheckView(String mode, boolean passed, Instant checkedAt, List<ChannelCheckItemView> items) {
    }

    /** 自检的一项。status：PASS、WARN、FAIL、SKIPPED；message 只说哪里不对，不带密钥、文件内容和服务器路径。 */
    public record ChannelCheckItemView(String code, String status, String message) {
    }
}
