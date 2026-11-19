package com.lowaltitude.inspection.rental.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 对外的形状，字段与契约 contracts/openapi-rental.yaml 逐个对应。

/** 询价身份：organizationName 只在记在组织名下时有；contactName 是账号的显示名（经办人）。 */
record RentalLesseeView(RentalLesseeKind kind, String organizationName, String contactName) {
}

record RentalMerchantView(UUID merchantId, String name) {
}

/** 询价的设备：商城供给在提交时的快照；referenceAmountMinor 和 referenceUnit 是商品页的参考价（如 ¥380/天 起）。 */
record RentalItemView(
        UUID offerId,
        UUID productId,
        String productName,
        String skuName,
        String coverUrl,
        Long referenceAmountMinor,
        String referenceUnit
) {
}

/** 使用地点：所选收货地址的快照。phone 给商家看时在付款之前打码。 */
record RentalPlaceView(
        UUID addressId,
        String recipientName,
        String phone,
        String provinceName,
        String cityCode,
        String cityName,
        String detail
) {
}

record RentalFeeView(String name, String note, long amountMinor) {
}

/** 一版报价。expired 表示已过有效期（按现在的时间算）。 */
record RentalQuoteView(
        int version,
        long dailyRentMinor,
        int days,
        int quantity,
        long rentMinor,
        List<RentalFeeView> fees,
        long totalMinor,
        boolean scheduleAvailable,
        OffsetDateTime validUntil,
        boolean expired,
        String terms,
        OffsetDateTime quotedAt
) {
}

/** 询价单上的租赁订单摘要（确认合同之后才有）。 */
record RentalOrderSummaryView(UUID rentalOrderId, RentalOrderStatus status, UUID tradeOrderId) {
}

record RentalInquiryView(
        UUID inquiryId,
        RentalInquiryStatus status,
        RentalCloseReason closeReason,
        String declineReason,
        RentalLesseeView lessee,
        RentalMerchantView merchant,
        RentalItemView item,
        int quantity,
        LocalDate startDate,
        LocalDate endDate,
        int days,
        RentalHandoverMode handoverMode,
        RentalReturnMode returnMode,
        RentalPlaceView place,
        String note,
        OffsetDateTime submittedAt,
        OffsetDateTime quoteDeadline,
        RentalQuoteView quote,
        boolean confirmable,
        boolean closable,
        RentalOrderSummaryView order,
        OffsetDateTime updatedAt,
        int version
) {
}

record RentalInquiryPageView(int page, int size, long total, boolean hasMore, List<RentalInquiryView> items) {
}

/** 确认合同的结果：去收银台付 tradeOrderId。 */
record RentalConfirmationView(UUID inquiryId, UUID rentalOrderId, UUID tradeOrderId, OffsetDateTime payBefore) {
}

/** 合同：确认时按报价和询价生成的信息，之后不变。 */
record RentalContractView(
        String contractNo,
        OffsetDateTime confirmedAt,
        int quoteVersion,
        RentalLesseeView lessee,
        RentalMerchantView lessor,
        String productName,
        String skuName,
        int quantity,
        LocalDate startDate,
        LocalDate endDate,
        int days,
        long dailyRentMinor,
        long rentMinor,
        List<RentalFeeView> fees,
        long totalMinor,
        RentalHandoverMode handoverMode,
        RentalReturnMode returnMode,
        RentalPlaceView place,
        String terms
) {
}

/** 付租金：交易订单、金额、付款截止和付款时间。 */
record RentalPaymentView(UUID tradeOrderId, long amountMinor, OffsetDateTime payBefore, OffsetDateTime paidAt) {
}

/** 交接或归还记录：商家录入，客户确认（confirmedAt 为空表示待你确认）。 */
record RentalRecordView(OffsetDateTime occurredAt, String note, OffsetDateTime recordedAt, OffsetDateTime confirmedAt) {
}

/** 交接前商家取消时的整单退款。status 是交易模块的退款状态。 */
record RentalRefundView(UUID refundId, long amountMinor, String status) {
}

record RentalOrderView(
        UUID rentalOrderId,
        UUID inquiryId,
        RentalOrderStatus status,
        RentalContractView contract,
        RentalPaymentView payment,
        RentalRecordView handoverRecord,
        RentalRecordView returnRecord,
        OffsetDateTime rentStartedAt,
        OffsetDateTime endedAt,
        OffsetDateTime closedAt,
        OffsetDateTime cancelledAt,
        String cancelReason,
        RentalRefundView refund,
        boolean handoverConfirmable,
        boolean returnConfirmable,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version
) {
}

record RentalOrderPageView(int page, int size, long total, boolean hasMore, List<RentalOrderView> items) {
}

/** 客户填的询价单。startDate 是 YYYY-MM-DD（北京时间）。 */
record RentalInquiryDraft(
        UUID offerId,
        Integer quantity,
        String startDate,
        Integer days,
        String handoverMode,
        String returnMode,
        UUID addressId,
        String note
) {
}

/** 商家的报价内容。 */
record RentalQuoteDraft(
        Long dailyRentMinor,
        Integer days,
        Integer quantity,
        List<RentalFeeDraft> fees,
        Boolean scheduleAvailable,
        OffsetDateTime validUntil,
        String terms
) {
}

record RentalFeeDraft(String name, String note, Long amountMinor) {
}

/** 商家录入的交接或归还记录；occurredAt 不填时是现在。 */
record RentalRecordDraft(OffsetDateTime occurredAt, String note) {
}

/** created 为 false 表示同一个幂等键重放，返回的是第一次建的询价单。 */
record RentalInquiryCreated(RentalInquiryView inquiry, boolean created) {
}

/** 询价身份：账号的默认组织是客户组织时给出组织 ID 和名称，否则两者都为空（个人）。 */
record RentalLessee(UUID organizationId, String organizationName, String contactName) {
}
