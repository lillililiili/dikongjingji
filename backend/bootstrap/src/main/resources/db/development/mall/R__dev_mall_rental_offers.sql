-- 仅开发环境：由 @Profile("dev") 的 mallDevelopmentDataFlyway 加载，生产不会执行。
-- F3 设备租赁：原型 PdRental 的“巡检机器狗 ¥380/天 起”出租方是示例机器人商家（厂家直营，东营仓交接）。
-- 它是唯一登记了收款账户、又有开发账号（dev-merchant）的商家，本地询价、报价、付租金、交接归还都用这一个供给。
-- 供给 ID 的算法和 R__dev_mall_catalog.sql 一样：md5('dev-mall-offer:' || 商品 || ':' || 商家)。
-- 排在 R__dev_mall_catalog.sql 之后（文件名顺序），商品和规格已经存在。

INSERT INTO mall_offer (
    id, sku_id, merchant_id, merchant_kind, city_code, price_amount_minor, price_unit,
    delivery_note, shipping_note, fulfilled_from, express_available, pickup_point
)
VALUES (
    md5('dev-mall-offer:401:1')::uuid,
    'c3000000-0000-4000-8000-000000000401',
    'c1000000-0000-4000-8000-000000000001',
    'FACTORY_DIRECT',
    '370500',
    38000,
    'DAY',
    '1 天起租 · 含操作讲解 · 押金不在线收取',
    '东营可租 · 商家送达或到店自提',
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
