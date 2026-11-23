package com.lowaltitude.inspection.mall.application;

import java.util.List;
import java.util.UUID;

/**
 * 批量导入（S2-3，contracts/openapi-ops-import.yaml 的 MALL 类）交给商城写的商品、参数、包装清单、规格和供给。
 * 校验（必填、长度、枚举、编号引用、收款账户）在导入工具里做完；ID 由导入工具的对照表定（第二次导入同一编号是更新）。
 * 编码都是字符串（和模板一致），这里再按库的约束兜底。
 */
public final class MallCatalogImport {

    private MallCatalogImport() {
    }

    /**
     * 一个商品。specs、packItems 为 null 表示这一批没带那张表，原有的不动；给了（可以是空的）就整份替换。
     * sellingPoints 同理。
     */
    public record Product(UUID productId, String reference, String category, String name, String summary,
                          String coverUrl, String saleMode, String experienceBadge, int sortOrder, String status,
                          List<String> sellingPoints, List<Spec> specs, List<PackItem> packItems, List<Sku> skus) {
    }

    public record Spec(String label, String value, int sortOrder) {
    }

    public record PackItem(String name, int quantity, int sortOrder) {
    }

    /** 一个规格和它的供给；没在这一批里的规格、供给不动。 */
    public record Sku(UUID skuId, String reference, String name, int sortOrder, List<Offer> offers) {
    }

    public record Offer(UUID offerId, String reference, UUID merchantId, String merchantKind, String cityCode,
                        Long priceAmountMinor, String priceUnit, Integer stockQuantity, boolean expressAvailable,
                        long shippingFeeMinor, String pickupPoint, String deliveryNote, String shippingNote,
                        String fulfilledFrom, String status) {
    }

    public enum Kind {
        PRODUCT,
        SKU,
        OFFER
    }

    /**
     * 写好的一个对象：targetId 是最终的 ID（同一规格、商家、城市已经有供给时用库里那一条，不是导入工具给的新 ID）。
     */
    public record Written(Kind kind, String reference, UUID targetId, boolean created) {
    }
}
