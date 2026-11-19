package com.lowaltitude.inspection.trade.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 支付渠道。一期只有微信支付平台收付通的普通支付：钱进商家（二级商户）自己的商户号，平台不经手。
 *
 * <p>渠道只负责“按渠道的格式发请求、验签、解密、把应答翻译成这里的结果”，不改订单。
 * 结果和订单对不对得上（商户号、单号、金额）由支付服务核对；核对不上不改订单。
 *
 * <p>失败一律抛 {@link PaymentChannelException}，按 {@link PaymentChannelException.Kind} 区分能不能重试。
 */
public interface PaymentChannel {

    /** 平台在渠道的身份（微信的服务商号）；渠道结果里的服务商号必须等于它。 */
    String providerId();

    /** App 下单，返回预支付 ID（微信 2 小时有效）。 */
    String createAppPayment(PrepayRequest request);

    /** 交给 App 调起支付的参数，每次现签。 */
    AppPayParams appPayParams(String prepayId);

    /** 按商户订单号查单。微信那边没有这一单时返回 {@link PaymentState#NOT_FOUND}。 */
    PaymentQuery queryPayment(String subMchid, String outTradeNo);

    /** 关单。只该在查单确认未支付后调用。 */
    void closePayment(String subMchid, String outTradeNo);

    /** 申请退款。成功只代表受理，结果以退款查询或退款回调为准。重试要沿用同一个退款单号。 */
    RefundAccepted applyRefund(RefundRequest request);

    /** 按退款单号查退款。微信那边没有这一笔时返回 {@link RefundState#NOT_FOUND}。 */
    RefundQuery queryRefund(String subMchid, String outRefundNo);

    /**
     * 验签并解密一条回调。headers 的键不分大小写；body 必须是收到的原始文本。
     * 验签不过、格式不对时抛 {@link PaymentChannelException.Kind#UNTRUSTED}，回调接口据此回 4xx 让渠道重发。
     */
    ChannelNotification parseNotification(Map<String, String> headers, String body);

    /**
     * 下载一个子商户某一天（北京时间自然日）的交易账单（全部：支付和退款）。微信每天 10 点后才生成前一天的账单；
     * 账单还在生成时抛 RETRYABLE（STATEMENT_CREATING）；那天没有交易时返回没有明细的账单。
     * 文件按微信给的 SHA1 校验，明细笔数和金额合计要和汇总行一致，否则抛 UNTRUSTED。
     */
    TradeBill tradeBill(String subMchid, LocalDate billDate);

    // ---- 请求 ----

    /**
     * @param subMchid    收款商家的子商户号（订单上的快照）
     * @param outTradeNo  支付单号，≤32 位，数字、字母和 _-*
     * @param description 商品描述，超过 127 个字符时截断
     * @param amountMinor 金额，分
     * @param payBefore   最晚付款时间（渠道会把它调到下单后 1 分钟到 15 天之间）
     */
    record PrepayRequest(String subMchid, String outTradeNo, String description, long amountMinor, Instant payBefore) {
    }

    /** App 用微信 SDK 调起支付需要的字段；iOS 的 packageValue 参数名是 package。 */
    record AppPayParams(String appId, String partnerId, String prepayId, String packageValue,
                        String nonceStr, String timeStamp, String sign) {
    }

    /**
     * @param refundMinor 本次退款，分
     * @param totalMinor  原订单金额，分
     * @param reason      退款原因，会下发给用户，超过 80 个字符时截断；可以为空
     */
    record RefundRequest(String subMchid, String outTradeNo, String outRefundNo,
                         long refundMinor, long totalMinor, String reason) {
    }

    // ---- 结果 ----

    enum PaymentState {
        /** 支付成功。 */
        SUCCESS,
        /** 支付成功后转入退款。说明钱曾经到过，也按“已付款”核对。 */
        REFUND,
        /** 未支付。 */
        NOTPAY,
        /** 已关闭。 */
        CLOSED,
        /** 渠道没有这一单（没下成功，或单号不对）。 */
        NOT_FOUND,
        /** 渠道给了这里不认识的状态（REVOKED、USERPAYING、PAYERROR 只出现在付款码支付，App 支付不该出现）。 */
        OTHER
    }

    /**
     * 查单或支付回调里的一单。金额字段在 NOT_FOUND 时为 null。
     *
     * @param totalMinor      订单金额
     * @param payerTotalMinor 用户实付（总额减代金券）；平台不发券，正常时等于 totalMinor
     */
    record PaymentQuery(PaymentState state, String rawState, String spMchid, String subMchid, String outTradeNo,
                        String transactionId, Long totalMinor, Long payerTotalMinor, String currency,
                        Instant succeededAt) {
    }

    record RefundAccepted(String refundId, String outRefundNo, long refundMinor, Instant acceptedAt) {
    }

    enum RefundState {
        SUCCESS, CLOSED, PROCESSING, ABNORMAL, NOT_FOUND, OTHER
    }

    record RefundQuery(RefundState state, String rawState, String subMchid, String outTradeNo, String outRefundNo,
                       String refundId, Long refundMinor, Long totalMinor, Instant succeededAt) {
    }

    /** 一份交易账单。lines 为空表示那天没有交易。 */
    record TradeBill(String subMchid, LocalDate billDate, List<BillLine> lines) {

        public TradeBill {
            lines = List.copyOf(lines);
        }
    }

    enum BillLineKind {
        /** 支付成功的订单行。 */
        PAYMENT,
        /** 发起退款成功（受理）的退款行；账单出了以后退款状态不再更新。 */
        REFUND,
        /** 别的（比如付款码撤销）。 */
        OTHER
    }

    /**
     * 账单的一行。金额都是分。
     *
     * @param occurredAt        支付成功时间，或退款受理时间
     * @param orderAmountMinor  订单金额（支付行），退款行为 0
     * @param refundAmountMinor 退款金额（退款行），支付行为 0
     * @param refundStatus      退款行的退款状态（出账时的：SUCCESS / PROCESSING / FAIL / CHANGE）
     */
    record BillLine(BillLineKind kind, String rawState, Instant occurredAt, String subMchid, String transactionId,
                    String outTradeNo, String refundId, String outRefundNo, String currency,
                    long orderAmountMinor, long refundAmountMinor, String refundStatus) {
    }

    /** 一条验签、解密过的回调。 */
    sealed interface ChannelNotification permits PaymentNotification, RefundNotification, OtherNotification {
        /** 渠道的通知 ID，同一通知重发时不变，用来去重。 */
        String notificationId();

        String eventType();
    }

    /** TRANSACTION.SUCCESS。 */
    record PaymentNotification(String notificationId, String eventType, PaymentQuery payment)
            implements ChannelNotification {
    }

    /** REFUND.SUCCESS / REFUND.ABNORMAL / REFUND.CLOSED。 */
    record RefundNotification(String notificationId, String eventType, RefundQuery refund)
            implements ChannelNotification {
    }

    /** 验签通过、但这里不处理的事件类型；照样回成功，免得渠道一直重发。 */
    record OtherNotification(String notificationId, String eventType) implements ChannelNotification {
    }
}
