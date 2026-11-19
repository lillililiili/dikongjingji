-- 仅开发环境：由 @Profile("dev") 的 tradeDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- E09：给活动示例数据里收费活动的两家主办机构登记“收款账户”，让本地能走收费报名、付款（模拟微信）和退款。
--   示例青少年科技机构：青少年无人机编程挑战赛；示例研学机构：机器人研学体验、黄河口低空研学一日营。
-- 子商户号是编的（和商城示例商家的 19000001xx 错开），不是真实商户。
-- 机构由活动开发数据建，所以这份数据排在活动开发数据之后（ModuleFlywayConfiguration）。

INSERT INTO trade_merchant_account (organization_id, sub_mchid, active)
VALUES
    ('a1000000-0000-4000-8000-000000000001', '1900000201', true),
    ('a1000000-0000-4000-8000-000000000002', '1900000202', true)
ON CONFLICT (organization_id) DO UPDATE
SET sub_mchid = EXCLUDED.sub_mchid,
    active = EXCLUDED.active,
    updated_at = now();
