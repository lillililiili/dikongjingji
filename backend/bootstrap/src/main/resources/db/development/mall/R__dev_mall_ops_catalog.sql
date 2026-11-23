-- 仅开发环境：由 @Profile("dev") 的 mallDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- S3-1 商品与供给 / S4-4（商城迁移 V004）：编程机器人探索套装（101）照原型 PdFull、PdSku 补卖点、多规格和库存，
-- 让 App 的规格面板和运营端都能看到这几种情况。示例数据，不是真实在售商品。
--   卖点：“图形化编程”“避障练习”（PdFull 名称下面，后面跟体验标识“支持模拟体验”）。
--   规格：标准套装（已有）、教室套装（6 台 + 教师端）、扩展传感器包（没有任何商家供应，规格面板里是“本商家暂不供应”）。
--   库存：示例机器人商家东营仓的标准套装现货 12 件、教室套装 3 件；其他商家不跟踪库存（空着）。
-- 在 R__dev_mall_catalog.sql 之后执行（文件名排序），那里已经建好商品 101、标准套装和各商家的供给。

INSERT INTO mall_sku (id, product_id, name, sort_order)
VALUES
    ('c3000000-0000-4000-8000-000000001011', 'c2000000-0000-4000-8000-000000000101', '教室套装（6 台 + 教师端）', 20),
    ('c3000000-0000-4000-8000-000000001012', 'c2000000-0000-4000-8000-000000000101', '扩展传感器包', 30)
ON CONFLICT (id) DO UPDATE
SET product_id = EXCLUDED.product_id,
    name = EXCLUDED.name,
    sort_order = EXCLUDED.sort_order,
    status = 'ACTIVE',
    updated_at = now();

INSERT INTO mall_offer (
    id, sku_id, merchant_id, merchant_kind, city_code, price_amount_minor, price_unit,
    delivery_note, shipping_note, fulfilled_from, express_available, pickup_point, stock_quantity
)
VALUES (
    md5('dev-mall-offer:101-class:1')::uuid,
    'c3000000-0000-4000-8000-000000001011',
    'c1000000-0000-4000-8000-000000000001',
    'FACTORY_DIRECT',
    '370500',
    738000,
    'ITEM',
    '东营现货 · 48 小时发货 · 一年保修',
    '东营可买 · 包邮 · 48 小时内发货',
    '示例机器人商家 东营仓',
    true,
    '东营低空与机器人体验馆 自提点（示例路 66 号）',
    3
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
    stock_quantity = EXCLUDED.stock_quantity,
    status = 'ACTIVE',
    updated_at = now();

-- 标准套装（示例机器人商家东营仓）现货 12 件（PdSku 头部“示例机器人商家 · 东营现货 12 件”）。
UPDATE mall_offer
SET stock_quantity = 12,
    updated_at = now()
WHERE id = md5('dev-mall-offer:101:1')::uuid;

INSERT INTO mall_product_selling_point (id, product_id, label, sort_order)
VALUES
    (md5('dev-mall-selling-point:101:1')::uuid, 'c2000000-0000-4000-8000-000000000101', '图形化编程', 10),
    (md5('dev-mall-selling-point:101:2')::uuid, 'c2000000-0000-4000-8000-000000000101', '避障练习', 20)
ON CONFLICT (id) DO UPDATE
SET product_id = EXCLUDED.product_id,
    label = EXCLUDED.label,
    sort_order = EXCLUDED.sort_order;
