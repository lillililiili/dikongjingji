-- 仅开发环境：由 @Profile("dev") 的 mallDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- 内容取自 2026-11-14 修订版原型的商城列表和商品详情，商家、商品、价格都是示例，不是真实在售商品。

INSERT INTO identity_organization (id, organization_code, organization_name, organization_type)
VALUES
    ('c1000000-0000-4000-8000-000000000001', 'DEV-MALL-ROBOT-MAKER', '示例机器人商家', 'PROVIDER'),
    ('c1000000-0000-4000-8000-000000000002', 'DEV-MALL-EDU-AGENT', '示例教育装备代理', 'PROVIDER'),
    ('c1000000-0000-4000-8000-000000000003', 'DEV-MALL-MAKER-STORE', '示例科创器材店', 'PROVIDER'),
    ('c1000000-0000-4000-8000-000000000004', 'DEV-MALL-DRONE-MAKER', '示例无人机厂家', 'PROVIDER'),
    ('c1000000-0000-4000-8000-000000000005', 'DEV-MALL-INDUSTRY-AGENT', '示例行业装备代理', 'PROVIDER'),
    ('c1000000-0000-4000-8000-000000000006', 'DEV-MALL-RENTAL', '示例设备租赁商', 'PROVIDER')
ON CONFLICT (id) DO UPDATE
SET organization_code = EXCLUDED.organization_code,
    organization_name = EXCLUDED.organization_name,
    organization_type = EXCLUDED.organization_type,
    updated_at = now();

INSERT INTO mall_product (id, category, name, summary, sale_mode, experience_badge, sort_order, status)
VALUES
    ('c2000000-0000-4000-8000-000000000101', 'ROBOT', '编程机器人探索套装', '图形化编程入门，适合 8 岁以上', 'BUY', 'ONLINE_EXPERIENCE', 10, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000102', 'ROBOT', '桌面六轴机械臂 教学版', '桌面级六轴，配套分拣教学案例', 'BUY', 'ONLINE_EXPERIENCE', 50, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000201', 'DRONE', '编程教学无人机 入门套装', '可编程，含教学课件', 'BUY', 'ONLINE_EXPERIENCE', 20, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000202', 'DRONE', '4K 航拍无人机 标准版', '便携机身，4K 视频', 'BUY', 'VENUE_DISPLAY', 30, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000203', 'DRONE', '行业测绘无人机', '定制配置 · 含培训 · 厂家直营', 'INQUIRY', NULL, 90, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000301', 'PARTS', '智能飞行电池 适配教学机', '适配编程教学无人机', 'BUY', NULL, 40, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000302', 'PARTS', '避障训练场地套件', '机器人避障闯关同款场地', 'BUY', NULL, 60, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000303', 'PARTS', '无人机配件套装', '桨叶、保护罩、充电管家', 'BUY', NULL, 70, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000401', 'RENTAL', '巡检机器狗', '园区巡逻与巡检演示', 'RENT', 'VENUE_DISPLAY', 80, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000402', 'RENTAL', '4K 航拍无人机 含飞手', '按天租，含一名飞手', 'RENT', NULL, 100, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000403', 'RENTAL', '行业测绘无人机 RTK 版', '按天租，含 RTK 模块', 'RENT', NULL, 110, 'PUBLISHED'),
    ('c2000000-0000-4000-8000-000000000404', 'RENTAL', '户外飞行训练场地 半天', '黄河口营地飞行场', 'RENT', NULL, 120, 'PUBLISHED'),
    -- 只在济南有供给：东营的列表和详情都不应返回。
    ('c2000000-0000-4000-8000-000000000901', 'ROBOT', '仅济南在售的示例机器人', NULL, 'BUY', NULL, 200, 'PUBLISHED'),
    -- 已下架：任何城市都不应返回。
    ('c2000000-0000-4000-8000-000000000902', 'DRONE', '已下架的示例无人机', NULL, 'BUY', NULL, 210, 'OFFLINE')
ON CONFLICT (id) DO UPDATE
SET category = EXCLUDED.category,
    name = EXCLUDED.name,
    summary = EXCLUDED.summary,
    sale_mode = EXCLUDED.sale_mode,
    experience_badge = EXCLUDED.experience_badge,
    sort_order = EXCLUDED.sort_order,
    status = EXCLUDED.status,
    updated_at = now();

-- 每个商品一个规格（规格多选留到“规格与配送”那一步）。规格编号 = 商品编号把 c2 换成 c3。
INSERT INTO mall_sku (id, product_id, name, sort_order)
SELECT replace(id::text, 'c2000000', 'c3000000')::uuid, id, '标准版', 10
FROM mall_product
WHERE id::text LIKE 'c2000000-%'
ON CONFLICT (id) DO UPDATE
SET product_id = EXCLUDED.product_id,
    name = EXCLUDED.name,
    sort_order = EXCLUDED.sort_order,
    updated_at = now();

INSERT INTO mall_offer (
    id, sku_id, merchant_id, merchant_kind, city_code, price_amount_minor, price_unit,
    delivery_note, shipping_note, fulfilled_from, express_available, pickup_point
)
SELECT md5('dev-mall-offer:' || seed.product || ':' || seed.merchant)::uuid,
       ('c3000000-0000-4000-8000-000000000' || seed.product)::uuid,
       ('c1000000-0000-4000-8000-00000000000' || seed.merchant)::uuid,
       seed.kind, seed.city, seed.amount, seed.unit, seed.delivery, seed.shipping, seed.fulfilled,
       -- 本地门店只能到店自提；示例机器人商家的东营仓两种都行（原型 Checkout 的写法）。
       seed.kind <> 'LOCAL_STORE' OR seed.unit <> 'ITEM',
       CASE
           WHEN seed.kind = 'LOCAL_STORE' AND seed.unit = 'ITEM' THEN '示例科创器材店 东营门店（示例路 18 号）'
           WHEN seed.product = '101' AND seed.merchant = '1' THEN '东营低空与机器人体验馆 自提点（示例路 66 号）'
       END
FROM (
    VALUES
        ('101', '1', 'FACTORY_DIRECT', '370500', 129900::bigint, 'ITEM', '东营现货 · 48 小时发货 · 一年保修', '东营可买 · 包邮 · 48 小时内发货', '示例机器人商家 东营仓'),
        ('101', '2', 'AUTHORIZED_DEALER', '370500', 135000, 'ITEM', '交期 7 天 · 含 2 课时教学', '东营可买 · 包邮 · 交期 7 天', '示例教育装备代理 合作仓'),
        ('101', '3', 'LOCAL_STORE', '370500', 138000, 'ITEM', '到店自提 · 门店 15 天无理由', '东营可买 · 无运费 · 门店自提', '示例科创器材店 东营门店'),
        ('102', '1', 'FACTORY_DIRECT', NULL, 980000, 'ITEM', '全国发货 · 交期 10 天', '全国可买 · 包邮 · 交期 10 天', '示例机器人商家 总仓'),
        ('102', '2', 'AUTHORIZED_DEALER', '370500', 1020000, 'ITEM', '含上门安装调试', '东营可买 · 包邮 · 交期 7 天', '示例教育装备代理 合作仓'),
        ('201', '4', 'FACTORY_DIRECT', NULL, 89900, 'ITEM', '全国发货 · 48 小时发货', '全国可买 · 包邮 · 48 小时内发货', '示例无人机厂家 总仓'),
        ('201', '2', 'AUTHORIZED_DEALER', '370500', 92000, 'ITEM', '含 2 课时教学', '东营可买 · 包邮 · 交期 5 天', '示例教育装备代理 合作仓'),
        ('201', '3', 'LOCAL_STORE', '370500', 95000, 'ITEM', '到店自提', '东营可买 · 无运费 · 门店自提', '示例科创器材店 东营门店'),
        ('202', '4', 'FACTORY_DIRECT', NULL, 499900, 'ITEM', '全国发货 · 一年保修', '全国可买 · 包邮 · 48 小时内发货', '示例无人机厂家 总仓'),
        ('202', '3', 'LOCAL_STORE', '370500', 519900, 'ITEM', '到店自提 · 可现场试飞讲解', '东营可买 · 无运费 · 门店自提', '示例科创器材店 东营门店'),
        ('203', '4', 'FACTORY_DIRECT', NULL, NULL, 'ITEM', '按配置报价 · 含 2 名飞手培训', NULL, '示例无人机厂家'),
        ('203', '5', 'AUTHORIZED_DEALER', '370500', NULL, 'ITEM', '按项目报价', NULL, '示例行业装备代理'),
        ('301', '4', 'FACTORY_DIRECT', NULL, 32900, 'ITEM', '全国发货', '全国可买 · 满 99 包邮 · 48 小时内发货', '示例无人机厂家 总仓'),
        ('301', '3', 'LOCAL_STORE', '370500', 32900, 'ITEM', '到店自提', '东营可买 · 无运费 · 门店自提', '示例科创器材店 东营门店'),
        ('302', '1', 'FACTORY_DIRECT', '370500', 46000, 'ITEM', '东营现货', '东营可买 · 包邮 · 48 小时内发货', '示例机器人商家 东营仓'),
        ('303', '4', 'FACTORY_DIRECT', NULL, 38600, 'ITEM', '型号需确认后再下单', '全国可买 · 包邮 · 48 小时内发货', '示例无人机厂家 总仓'),
        ('401', '6', 'LOCAL_STORE', '370500', 38000, 'DAY', '含操作培训 · 需押金', '东营可租 · 送机上门', '示例设备租赁商 东营站'),
        ('402', '6', 'LOCAL_STORE', '370500', 26000, 'DAY', '含一名飞手', '东营可租 · 现场交付', '示例设备租赁商 东营站'),
        ('402', '4', 'FACTORY_DIRECT', '370500', 30000, 'DAY', '厂家飞手 · 含保险', '东营可租 · 现场交付', '示例无人机厂家 东营服务点'),
        ('403', '5', 'AUTHORIZED_DEALER', '370500', 120000, 'DAY', '含 RTK 模块与数据处理软件', '东营可租 · 现场交付', '示例行业装备代理'),
        ('404', '6', 'LOCAL_STORE', '370500', 80000, 'HALF_DAY', '含场地保险', '东营可订 · 到场使用', '黄河口户外飞行营地'),
        ('901', '1', 'FACTORY_DIRECT', '370100', 99900, 'ITEM', '济南现货', '济南可买', '示例机器人商家 济南仓'),
        ('902', '4', 'FACTORY_DIRECT', NULL, 199900, 'ITEM', NULL, NULL, NULL)
) AS seed(product, merchant, kind, city, amount, unit, delivery, shipping, fulfilled)
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
    updated_at = now();

INSERT INTO mall_product_spec (id, product_id, label, value, sort_order)
SELECT md5('dev-mall-spec:' || seed.label)::uuid,
       'c2000000-0000-4000-8000-000000000101', seed.label, seed.value, seed.sort_order
FROM (
    VALUES
        ('适用年龄', '8 岁以上', 10),
        ('编程方式', '图形化 / Python', 20),
        ('传感器', '超声波、循线、陀螺仪', 30),
        ('续航', '约 90 分钟', 40),
        ('连接', '蓝牙 5.0', 50),
        ('配套课程', '16 课时（数字版）', 60)
) AS seed(label, value, sort_order)
ON CONFLICT (id) DO UPDATE
SET label = EXCLUDED.label,
    value = EXCLUDED.value,
    sort_order = EXCLUDED.sort_order,
    updated_at = now();

INSERT INTO mall_product_pack_item (id, product_id, name, quantity, sort_order)
SELECT md5('dev-mall-pack:' || seed.name)::uuid,
       'c2000000-0000-4000-8000-000000000101', seed.name, seed.quantity, seed.sort_order
FROM (
    VALUES
        ('机器人主机', 1, 10),
        ('充电线', 1, 20),
        ('避障道具', 1, 30),
        ('快速上手指南', 1, 40)
) AS seed(name, quantity, sort_order)
ON CONFLICT (id) DO UPDATE
SET name = EXCLUDED.name,
    quantity = EXCLUDED.quantity,
    sort_order = EXCLUDED.sort_order,
    updated_at = now();
