package com.lowaltitude.inspection.mall.application;

// 运营端“商品与供给”的稳定编码（契约 contracts/openapi-ops-catalog.yaml 1.0.0），与迁移 V001、V004 的 CHECK 约束一致。
// 放在一个文件里：都是只有几个值的小枚举。

/** 商品状态：DRAFT 草稿；PUBLISHED 已上架；OFFLINE 已下架。（库里还有 DELETED，运营端不用、不显示。） */
enum OpsProductStatus {
    DRAFT,
    PUBLISHED,
    OFFLINE
}

/** 规格状态。 */
enum OpsSkuStatus {
    ACTIVE,
    DISABLED
}

/** 供给状态。 */
enum OpsOfferStatus {
    ACTIVE,
    DISABLED
}

enum OpsProductStatusAction {
    PUBLISH,
    UNPUBLISH
}

enum OpsOfferStatusAction {
    ACTIVATE,
    DEACTIVATE
}

/** 图文介绍的一段：文字或一张图。 */
enum OpsIntroBlockType {
    TEXT,
    IMAGE
}

/** 上架前还缺什么。 */
enum OpsPublishBlocker {
    SUMMARY_MISSING,
    NO_ACTIVE_SKU,
    NO_ACTIVE_OFFER
}

/** 商品的操作记录（留痕）。 */
enum OpsCatalogAction {
    PRODUCT_CREATE,
    PRODUCT_UPDATE,
    PRODUCT_PUBLISH,
    PRODUCT_UNPUBLISH,
    SKU_CREATE,
    SKU_UPDATE,
    OFFER_CREATE,
    OFFER_UPDATE,
    OFFER_PRICE_CHANGE,
    OFFER_ACTIVATE,
    OFFER_DEACTIVATE,
    IMPORT
}
