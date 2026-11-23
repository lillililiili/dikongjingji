package com.lowaltitude.inspection.mall.application;

import java.util.UUID;

/** 读库得到的行。由持久层填充，读服务把它们转成对外的视图。 */
public final class MallRows {

    private MallRows() {
    }

    public record ProductCardRow(
            UUID productId,
            String category,
            String name,
            String coverUrl,
            String saleMode,
            String experienceBadge,
            Long fromAmountMinor,
            String priceUnit,
            long distinctPriceCount,
            long sellerCount
    ) {
    }

    public record ProductRow(
            UUID productId,
            String category,
            String name,
            String summary,
            String coverUrl,
            String saleMode,
            String experienceBadge
    ) {
    }

    public record OfferRow(
            UUID offerId,
            UUID skuId,
            String skuName,
            UUID merchantId,
            String merchantName,
            String merchantKind,
            Long amountMinor,
            String currency,
            String priceUnit,
            String deliveryNote,
            String shippingNote,
            String fulfilledFrom,
            Integer stockQuantity
    ) {
    }

    public record SpecRow(String label, String value) {
    }

    public record PackItemRow(String name, int quantity) {
    }

    /** 商品的一个启用规格（1.3.0）。 */
    public record SkuRow(UUID skuId, String name) {
    }

    /** 图文介绍的一段（1.3.0）。 */
    public record IntroBlockRow(String blockType, String text, String imageUrl) {
    }

    /** 1.4.0：一个供给的开票方、售后责任方（已按供给 → 商家资料 → 商家名称取好）和商品是否支持七天无理由。 */
    public record TradeTermsRow(UUID offerId, String invoiceIssuer, String aftersaleParty, boolean noReasonReturn) {
    }
}
