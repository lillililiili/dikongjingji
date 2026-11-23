-- 仅开发环境：由 @Profile("dev") 的 tradeDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- S4-1：给巡检示例服务商“云翼科技”（dev-provider 是它的操作员，开发种子里的巡检服务都归它）登记“收款账户”，
-- 让本地能走巡检付款节点：确认带付款节点的方案、付首付和按次服务费（模拟微信）、取消时首付原路退款。
-- 子商户号是编的（和商城、收费报名、场馆示例错开），不是真实商户。
-- 机构由身份开发数据建，身份开发数据排在所有模块之前（ModuleFlywayConfiguration）。

INSERT INTO trade_merchant_account (organization_id, sub_mchid, active)
VALUES
    ('22222222-2222-2222-2222-222222222222', '1900000301', true)
ON CONFLICT (organization_id) DO UPDATE
SET sub_mchid = EXCLUDED.sub_mchid,
    active = EXCLUDED.active,
    updated_at = now();
