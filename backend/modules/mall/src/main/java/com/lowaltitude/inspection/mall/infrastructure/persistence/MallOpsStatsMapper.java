package com.lowaltitude.inspection.mall.infrastructure.persistence;

import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里商城的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 */
public interface MallOpsStatsMapper {

    /** 在售商品：已上架、有在用规格和在售报价。 */
    @Select("""
            SELECT COUNT(DISTINCT p.id) FROM mall_product p
            JOIN mall_sku s ON s.product_id = p.id
            JOIN mall_offer o ON o.sku_id = s.id
            WHERE p.status = 'PUBLISHED'
              AND s.status = 'ACTIVE'
              AND o.status = 'ACTIVE'
            """)
    long products();

    /** 有在售报价的商家。 */
    @Select("""
            SELECT COUNT(DISTINCT o.merchant_id) FROM mall_product p
            JOIN mall_sku s ON s.product_id = p.id
            JOIN mall_offer o ON o.sku_id = s.id
            WHERE p.status = 'PUBLISHED'
              AND s.status = 'ACTIVE'
              AND o.status = 'ACTIVE'
            """)
    long sellingMerchants();

    /** 待审的商家资料。 */
    @Select("""
            SELECT COUNT(*) FROM mall_merchant_profile_submission WHERE status = 'SUBMITTED'
            """)
    long pendingMerchantProfiles();
}
