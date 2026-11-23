package com.lowaltitude.inspection.opsimport.application;

/**
 * 改动记录里的对象类别。PRODUCT、SKU、OFFER 还记在编号对照表里（跨批次，第二次导入同一编号是更新）；
 * 经营主体按 organization_code、操作员按登录名、收款账户按机构本身就能找到，不进对照表。
 */
public enum OpsImportRefKind {
    ORGANIZATION,
    OPERATOR,
    MERCHANT_ACCOUNT,
    PRODUCT,
    SKU,
    OFFER
}
