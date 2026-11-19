-- 仅开发环境：由 @Profile("dev") 的 tradeDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- E10：给体验示例数据里两家场馆的运营机构登记“收款账户”，让本地能走到馆预约、付款（模拟微信）和退款。
--   东营低空与机器人体验馆（dev-organizer 是它的操作员，可以核销这家场馆的到馆码）；黄河口户外飞行营地。
-- 子商户号是编的（和商城、收费报名示例错开），不是真实商户。
-- 机构由身份、活动、体验的开发数据建，这份数据排在活动开发数据之后（ModuleFlywayConfiguration）。

INSERT INTO trade_merchant_account (organization_id, sub_mchid, active)
VALUES
    ('a1000000-0000-4000-8000-000000000004', '1900000204', true),
    ('a1000000-0000-4000-8000-000000000005', '1900000205', true)
ON CONFLICT (organization_id) DO UPDATE
SET sub_mchid = EXCLUDED.sub_mchid,
    active = EXCLUDED.active,
    updated_at = now();
