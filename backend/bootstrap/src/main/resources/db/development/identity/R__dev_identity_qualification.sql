-- 仅开发环境：由 @Profile("dev") 的 identityDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- R6-4 资质已审核（身份迁移 V005）：给 2026-11-14 修订版原型上标了“资质已审核”的示例机构写上审核通过时间。
-- 正式环境由平台运营在线下核对资质后写入，没有审核页面。
--   云翼科技        巡检服务详情、方案确认、订单卡（画板 InspDetail、PlanConfirm、OrderList 的服务商）
--   鹰眼智能巡检    航拍服务详情、服务商主页、需求报价（画板 SvcAerial、ProviderPage、Quote）
--   示例机器人商家  询价报价、租赁报价和租赁订单的出租方、设备维护和技术支持服务（画板 QuoteGoods、RentalQuote、OrderRental）
--   示例无人机厂家  询价报价（画板 QuoteGoods）
-- 故意不写的，用来看“不显示”：示例行业装备代理（询价报价的第三家）、示例测绘服务商（测绘服务详情、服务商主页）、
-- 示例设备租赁商，以及其余示例机构。
-- 这些机构由巡检、商城的开发种子负责名称和资料；那几份种子可能排在这份之后执行，所以这里先按同一个 ID 和编码建一次，
-- 冲突时只改审核时间，谁先执行都一样。

INSERT INTO identity_organization (id, organization_code, organization_name, organization_type, qualification_reviewed_at)
VALUES
    ('22222222-2222-2222-2222-222222222222', 'DEV-PROVIDER-YUNYI', '云翼科技', 'PROVIDER', '2026-11-01 10:00:00+08'),
    ('f2000000-0000-4000-8000-000000000001', 'DEV-PROVIDER-EAGLE', '鹰眼智能巡检', 'PROVIDER', '2026-11-01 10:00:00+08'),
    ('c1000000-0000-4000-8000-000000000001', 'DEV-MALL-ROBOT-MAKER', '示例机器人商家', 'PROVIDER', '2026-11-02 10:00:00+08'),
    ('c1000000-0000-4000-8000-000000000004', 'DEV-MALL-DRONE-MAKER', '示例无人机厂家', 'PROVIDER', '2026-11-02 10:00:00+08')
ON CONFLICT (id) DO UPDATE
SET qualification_reviewed_at = EXCLUDED.qualification_reviewed_at,
    updated_at = now();
