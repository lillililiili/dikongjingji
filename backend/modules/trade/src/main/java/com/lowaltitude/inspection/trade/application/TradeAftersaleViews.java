package com.lowaltitude.inspection.trade.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderLineView;

/** 商品售后（S4-2）对外的形状，字段与契约 contracts/openapi-trade-aftersale.yaml 逐个对应。 */
public final class TradeAftersaleViews {

    private TradeAftersaleViews() {
    }

    /**
     * 一张售后单。买家和商家看到的形状相同；寄回地址只在商家同意之后才有。actions 是这一方现在能做的事。
     */
    public record AftersaleView(
            UUID aftersaleId,
            String aftersaleNo,
            UUID orderId,
            String orderNo,
            String kind,
            String reason,
            String description,
            String status,
            AftersaleMerchantView merchant,
            List<TradeOrderLineView> lines,
            long paidMinor,
            long requestedMinor,
            Long refundMinor,
            AftersaleReturnAddressView returnAddress,
            AftersaleWaybillView returnWaybill,
            String rejectReason,
            String merchantNote,
            AftersaleRefundView refund,
            List<AftersaleImageView> images,
            Instant dueAt,
            Instant submittedAt,
            Instant reviewedAt,
            Instant returnShippedAt,
            Instant receivedAt,
            Instant refundedAt,
            Instant closedAt,
            List<AftersaleEventView> timeline,
            AftersaleActionsView actions,
            int version
    ) {
    }

    /** 处理方：卖出商品的商家；aftersaleParty 是下单时记的售后责任方（没有时为 null，就是商家自己）。 */
    public record AftersaleMerchantView(UUID organizationId, String name, String aftersaleParty) {
    }

    public record AftersaleReturnAddressView(String contactName, String phone, String address, String note) {
    }

    public record AftersaleWaybillView(String carrierName, String trackingNo, Instant shippedAt) {
    }

    /**
     * 经交易模块发起的退款：status 是退款单的状态（REQUESTED / PROCESSING / SUCCEEDED / CLOSED / ABNORMAL）；
     * channelTransactionId 是原付款在微信的交易号（只显示）。
     */
    public record AftersaleRefundView(String status, long amountMinor, Instant succeededAt, String channelTransactionId) {
    }

    public record AftersaleImageView(UUID imageId, String slot) {
    }

    public record AftersaleEventView(String action, String status, Instant occurredAt) {
    }

    /**
     * 买家：canRevoke 撤销；canSubmitReturn 填运单；canEditReturn 改运单；canReapply 重新申请（这张单结束了、订单还能申请）。
     * 商家：canReview 同意或拒绝；canConfirmReceipt 确认收货并退款；canRejectReceipt 验货不通过。另一方的几项总是 false。
     */
    public record AftersaleActionsView(
            boolean canRevoke,
            boolean canSubmitReturn,
            boolean canEditReturn,
            boolean canReapply,
            boolean canReview,
            boolean canConfirmReceipt,
            boolean canRejectReceipt
    ) {
    }

    /** 列表的一页；counts 是三个页签各有几张（售后中、已完成、已关闭）。 */
    public record AftersalePageView(int page, int size, long total, boolean hasMore, List<AftersaleView> items,
                                    AftersaleCountsView counts) {
    }

    public record AftersaleCountsView(long doing, long done, long closed) {
    }

    /**
     * 售后申请页要的：这张单能选哪类、哪个原因（不能选的带 unavailableReason），最多能退多少、默认申请多少，谁处理。
     * openAftersaleId：有没结束的售后单时是它（页面去它的详情）。
     */
    public record AftersaleEligibilityView(
            UUID orderId,
            String orderNo,
            String orderStatus,
            List<TradeOrderLineView> lines,
            long paidMinor,
            long maxRefundMinor,
            AftersaleMerchantView merchant,
            List<AftersaleOptionView> kinds,
            List<AftersaleReasonOptionView> reasons,
            UUID openAftersaleId,
            Instant shippedAt,
            Instant completedAt
    ) {
    }

    public record AftersaleOptionView(String kind, boolean available, String unavailableReason) {
    }

    /** kind 是这个原因属于哪一类售后。 */
    public record AftersaleReasonOptionView(String reason, String kind, boolean available, String unavailableReason) {
    }

    public record AftersaleImageUploadView(UUID imageId, String slot, String contentType, int sizeBytes) {
    }

    public record ReturnAddressView(String contactName, String phone, String address, String note, int version,
                                    Instant updatedAt) {
    }
}
