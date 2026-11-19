-- 仅开发环境：由 @Profile("dev") 的 mallDevelopmentDataFlyway 加载，生产不会执行。
-- F4 询价商品：原型 PdInquiryFull 的“行业测绘无人机 · 3 家可报价 · 东营可售”。商城示例数据里这件商品已有示例无人机厂家（全国）
-- 和示例行业装备代理（东营）两家供给；这里再加示例机器人商家（dev-merchant 的机构）在东营的一家，凑齐 3 家，
-- 本地可以用 infra/scripts/dev-inquiry-merchant.cjs 替它报价。询价供给没有标价。
-- 供给 ID 的算法和 R__dev_mall_catalog.sql 一样：md5('dev-mall-offer:' || 商品 || ':' || 商家)。

INSERT INTO mall_offer (
    id, sku_id, merchant_id, merchant_kind, city_code, price_amount_minor, price_unit,
    delivery_note, shipping_note, fulfilled_from, express_available, pickup_point
)
VALUES (
    md5('dev-mall-offer:203:1')::uuid,
    'c3000000-0000-4000-8000-000000000203',
    'c1000000-0000-4000-8000-000000000001',
    'FACTORY_DIRECT',
    '370500',
    NULL,
    'ITEM',
    '按配置报价 · 东营交付 · 可含飞手培训',
    '东营可售 · 交期以报价为准',
    '示例机器人商家 东营仓',
    true,
    NULL
)
ON CONFLICT (id) DO UPDATE
SET sku_id = EXCLUDED.sku_id,
    merchant_id = EXCLUDED.merchant_id,
    merchant_kind = EXCLUDED.merchant_kind,
    city_code = EXCLUDED.city_code,
    price_amount_minor = EXCLUDED.price_amount_minor,
    price_unit = EXCLUDED.price_unit,
    delivery_note = EXCLUDED.delivery_note,
    shipping_note = EXCLUDED.shipping_note,
    fulfilled_from = EXCLUDED.fulfilled_from,
    express_available = EXCLUDED.express_available,
    pickup_point = EXCLUDED.pickup_point,
    status = 'ACTIVE',
    updated_at = now();
