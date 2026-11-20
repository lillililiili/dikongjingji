package com.lowaltitude.inspection.mall.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 对外的形状，字段与契约 contracts/openapi-mall-inquiry.yaml 逐个对应。商家用到的商家信息复用 MallMerchantView。

/** 询价身份（只给客户自己看）。 */
record MallInquiryBuyerView(MallInquiryBuyerKind kind, String organizationName, String contactName) {
}

record MallInquiryProductView(UUID productId, String name, MallMediaView coverImage) {
}

/** 发给的一家商家：所选的供给和这家的状态。 */
record MallInquiryMerchantView(
        UUID offerId,
        MallMerchantView merchant,
        MallInquiryInvitationStatus status,
        String declineReason
) {
}

/** 选定的报价摘要；orderId 是用它最近下的那张商城订单（还没下单时为 null）。 */
record MallInquiryChosenView(
        UUID quoteId,
        int quoteVersion,
        UUID offerId,
        MallMerchantView merchant,
        long unitAmountMinor,
        int quantity,
        long totalMinor,
        OffsetDateTime validUntil,
        OffsetDateTime chosenAt,
        UUID orderId
) {
}

record MallInquiryView(
        UUID inquiryId,
        MallInquiryStatus status,
        MallInquiryCloseReason closeReason,
        MallInquiryBuyerView buyer,
        MallInquiryProductView product,
        String requirement,
        int quantity,
        String cityCode,
        LocalDate expectedDate,
        List<MallInquiryMerchantView> merchants,
        OffsetDateTime submittedAt,
        OffsetDateTime quoteDeadline,
        boolean quotingOpen,
        int quoteCount,
        MallInquiryChosenView chosen,
        boolean closable,
        OffsetDateTime updatedAt,
        int version
) {
}

record MallInquiryPageView(int page, int size, long total, boolean hasMore, List<MallInquiryView> items) {
}

/** 报价里的商家：qualificationReviewed 是平台审核过它的资质（R6-4），页面据此显示“资质已审核”。 */
record MallInquiryQuoteMerchantView(UUID organizationId, String name, MallMerchantKind kind,
                                    boolean qualificationReviewed) {
}

/** 客户看到的一份报价（每家最新一版）。choosable：现在可以选它。 */
record MallInquiryQuoteView(
        UUID quoteId,
        int version,
        UUID offerId,
        MallInquiryQuoteMerchantView merchant,
        MallInquiryQuoteStatus status,
        long unitAmountMinor,
        int quantity,
        long totalMinor,
        String model,
        int leadDays,
        String includes,
        OffsetDateTime validUntil,
        OffsetDateTime quotedAt,
        boolean choosable
) {
}

/** 询价单详情：询价单和各家报价（按 sort 排）。 */
record MallInquiryDetailView(MallInquiryView inquiry, MallInquirySort sort, List<MallInquiryQuoteView> quotes) {
}

/** 商品在这个城市的询价供给：canInquire 为 false 表示这家没开通在线收款，不能发给它。 */
record MallInquiryOfferView(UUID offerId, MallMerchantView merchant, String deliveryNote, boolean canInquire) {
}

record MallInquiryOfferListView(UUID productId, String cityCode, List<MallInquiryOfferView> items) {
}

/** 商家自己的报价（最新一版）。 */
record MallMerchantQuoteView(
        UUID quoteId,
        int version,
        MallInquiryQuoteStatus status,
        long unitAmountMinor,
        int quantity,
        long totalMinor,
        String model,
        int leadDays,
        String includes,
        OffsetDateTime validUntil,
        OffsetDateTime quotedAt
) {
}

/** 商家看到的询价单：只有商品、配置说明、数量、城市和日期，没有客户的身份和联系方式。 */
record MallMerchantInquiryView(
        UUID inquiryId,
        MallInquiryStatus status,
        MallInquiryProductView product,
        UUID offerId,
        String requirement,
        int quantity,
        String cityCode,
        LocalDate expectedDate,
        OffsetDateTime submittedAt,
        OffsetDateTime quoteDeadline,
        boolean quotingOpen,
        MallInquiryInvitationStatus invitationStatus,
        String declineReason,
        MallMerchantQuoteView myQuote
) {
}

record MallMerchantInquiryPageView(int page, int size, long total, boolean hasMore,
                                   List<MallMerchantInquiryView> items) {
}

/** 客户填的询价单。expectedDate 是 YYYY-MM-DD（北京时间），可以不填。 */
record MallInquiryDraft(
        UUID productId,
        String requirement,
        Integer quantity,
        String cityCode,
        String expectedDate,
        List<UUID> offerIds
) {
}

record MallInquiryQuoteDraft(
        Long unitAmountMinor,
        String model,
        Integer leadDays,
        String includes,
        OffsetDateTime validUntil
) {
}

record MallInquiryCreated(MallInquiryView inquiry, boolean created) {
}

/** 询价身份：默认组织是客户组织时给出组织 ID 和名称，否则两者为空（个人）。 */
record MallInquiryBuyer(UUID organizationId, String organizationName, String contactName) {
}

/** 下单用的报价：供给、数量（询价单的）、单价、型号配置。lastOrderId 是用它下过的上一张订单。 */
record MallInquiryOrderQuote(UUID quoteId, UUID offerId, int quantity, long unitAmountMinor, String model,
                             UUID lastOrderId) {
}
