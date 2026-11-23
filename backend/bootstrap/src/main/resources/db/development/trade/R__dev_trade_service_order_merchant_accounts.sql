-- 仅开发环境：由 @Profile("dev") 的 tradeDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- S4-3：给四类专业服务的示例服务商登记“收款账户”，让本地能走通用服务订单的一次付清（模拟微信）和取消退款：
-- 云翼科技（dev-provider 的机构，和 S4-1 巡检付款节点用同一个号）、鹰眼智能巡检、示例测绘服务商。
-- 示例机器人商家（设备维护）在 R__dev_trade_merchant_accounts.sql 里已经登记。子商户号是编的，不是真实商户。
-- 机构由身份和巡检的开发数据建，排在它们之后（ModuleFlywayConfiguration）。

INSERT INTO trade_merchant_account (organization_id, sub_mchid, active)
VALUES
    ('22222222-2222-2222-2222-222222222222', '1900000301', true),
    ('f2000000-0000-4000-8000-000000000001', '1900000401', true),
    ('f2000000-0000-4000-8000-000000000002', '1900000402', true)
ON CONFLICT (organization_id) DO UPDATE
SET sub_mchid = EXCLUDED.sub_mchid,
    active = EXCLUDED.active,
    updated_at = now();
