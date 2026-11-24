package com.lowaltitude.inspection.mall.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 运营端“商品与供给”对外的视图（契约 contracts/openapi-ops-catalog.yaml 1.0.0），字段和契约一一对应。 */
public final class MallOpsViews {

    private MallOpsViews() {
    }

    /** ownerOrganizationId / ownerName：商家自己建的商品带建它的商家（1.1.0）；平台建的为 null。 */
    public record OpsProductSummaryView(UUID productId, MallCategory category, String name, MallSaleMode saleMode,
                                        OpsProductStatus status, String coverUrl, int skuCount, int activeOfferCount,
                                        OffsetDateTime updatedAt, int version, UUID ownerOrganizationId,
                                        String ownerName) {
    }

    public record OpsProductPageView(int page, int size, long total, boolean hasMore,
                                     List<OpsProductSummaryView> items) {
    }

    public record OpsProductView(UUID productId, MallCategory category, String name, String summary, String coverUrl,
                                 MallSaleMode saleMode, MallExperienceBadge experienceBadge, int sortOrder,
                                 OpsProductStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                                 int version, UUID ownerOrganizationId, String ownerName) {
    }

    public record OpsSpecView(String label, String value) {
    }

    public record OpsPackItemView(String name, int quantity) {
    }

    public record OpsIntroBlockView(OpsIntroBlockType type, String text, String imageUrl) {
    }

    public record OpsSkuView(UUID skuId, String name, int sortOrder, OpsSkuStatus status, int version) {
    }

    public record OpsOfferView(UUID offerId, UUID skuId, String skuName, UUID merchantId, String merchantName,
                               MallMerchantKind merchantKind, String cityCode, String cityName, Long priceAmountMinor,
                               MallPriceUnit priceUnit, Integer stockQuantity, boolean expressAvailable,
                               long shippingFeeMinor, String pickupPoint, String deliveryNote, String shippingNote,
                               String fulfilledFrom, OpsOfferStatus status, boolean merchantCollecting,
                               OffsetDateTime updatedAt, int version) {
    }

    public record OpsCatalogOperationView(UUID operationId, OpsCatalogAction action, String detail,
                                          String operatorName, OffsetDateTime occurredAt) {
    }

    public record OpsProductDetailView(OpsProductView product, List<String> sellingPoints, List<OpsSpecView> specs,
                                       List<OpsPackItemView> packItems, List<String> gallery,
                                       List<OpsIntroBlockView> introBlocks, List<OpsSkuView> skus,
                                       List<OpsOfferView> offers, List<OpsPublishBlocker> publishBlockers,
                                       List<OpsCatalogOperationView> operations) {
    }

    /** created 为 false 表示同一个幂等键重放。 */
    public record OpsProductCreated(OpsProductDetailView detail, boolean created) {
    }

    public record OpsMediaView(UUID mediaId, String url, String contentType, long sizeBytes) {
    }
}
