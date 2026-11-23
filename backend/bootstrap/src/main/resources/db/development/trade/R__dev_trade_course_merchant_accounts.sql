-- 仅开发环境：由 @Profile("dev") 的 tradeDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- S5-5：给示例线上课的开课机构“示例培训机构”登记收款账户，让本地能走课程购买、付款（模拟微信）和退款。
-- 子商户号是编的（和商城示例商家的 19000001xx、活动主办机构的 19000002xx 错开），不是真实商户。
-- 机构由活动开发数据建，所以这份数据排在活动开发数据之后（ModuleFlywayConfiguration）。

INSERT INTO trade_merchant_account (organization_id, sub_mchid, active)
VALUES
    ('a1000000-0000-4000-8000-000000000003', '1900000301', true)
ON CONFLICT (organization_id) DO UPDATE
SET sub_mchid = EXCLUDED.sub_mchid,
    active = EXCLUDED.active,
    updated_at = now();
