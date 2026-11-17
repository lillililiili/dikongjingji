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
            String fulfilledFrom
    ) {
    }

    public record SpecRow(String label, String value) {
    }

    public record PackItemRow(String name, int quantity) {
    }
}
