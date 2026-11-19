-- 仅开发环境：由 @Profile("dev") 的 tradeDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- 给商城示例数据里的三个商家登记“收款账户”，让本地能提交订单。
-- 子商户号是编的（借用微信文档示例的号段），不是真实商户，不能用来付款：付款要等 E04 和真实的服务商号、子商户号。
-- 示例无人机厂家、行业装备代理、设备租赁商故意不登记，用来看“该商家暂未开通在线支付”。

INSERT INTO trade_merchant_account (organization_id, sub_mchid, active)
VALUES
    ('c1000000-0000-4000-8000-000000000001', '1900000101', true),
    ('c1000000-0000-4000-8000-000000000002', '1900000102', true),
    ('c1000000-0000-4000-8000-000000000003', '1900000103', true)
ON CONFLICT (organization_id) DO UPDATE
SET sub_mchid = EXCLUDED.sub_mchid,
    active = EXCLUDED.active,
    updated_at = now();
