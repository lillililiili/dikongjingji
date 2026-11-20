-- 仅开发环境：由 @Profile("dev") 的 tradeDevelopmentDataFlyway 加载，生产不会执行。
-- F4 询价商品要两家能收款的商家报价、比选：给示例行业装备代理登记收款账户（子商户号是编的，借用微信文档示例的号段），
-- 并加一个开发账号 dev-merchant-2 作它的操作员（infra/scripts/dev-inquiry-merchant.cjs --as dev-merchant-2）。
-- 示例设备租赁商仍然不登记，用来看“该商家暂未开通在线支付”（示例无人机厂家 2026-11-20 起在 R__dev_trade_merchant_accounts.sql 登记）。
-- 机构由商城开发种子建，所以这份排在商城开发数据之后。密码和 R__dev_identity_accounts.sql 的开发账号一样，是公开的。

INSERT INTO trade_merchant_account (organization_id, sub_mchid, active)
VALUES ('c1000000-0000-4000-8000-000000000005', '1900000105', true)
ON CONFLICT (organization_id) DO UPDATE
SET sub_mchid = EXCLUDED.sub_mchid,
    active = EXCLUDED.active,
    updated_at = now();

INSERT INTO identity_user (id, login_name, display_name, password_hash, default_organization_id)
VALUES (
    'c1000000-0000-4000-8000-0000000000a5',
    'dev-merchant-2',
    '示例行业装备代理操作员',
    crypt('test-password', gen_salt('bf', 10)),
    'c1000000-0000-4000-8000-000000000005'
)
ON CONFLICT (id) DO UPDATE
SET login_name = EXCLUDED.login_name,
    display_name = EXCLUDED.display_name,
    password_hash = EXCLUDED.password_hash,
    default_organization_id = EXCLUDED.default_organization_id,
    status = 'ACTIVE';

INSERT INTO identity_organization_member (organization_id, user_id)
VALUES ('c1000000-0000-4000-8000-000000000005', 'c1000000-0000-4000-8000-0000000000a5')
ON CONFLICT (organization_id, user_id) DO UPDATE
SET member_status = 'ACTIVE';

INSERT INTO identity_user_role (user_id, role_id, organization_id)
SELECT 'c1000000-0000-4000-8000-0000000000a5', id, 'c1000000-0000-4000-8000-000000000005'
FROM identity_role WHERE role_code = 'PROVIDER_OPERATOR'
ON CONFLICT DO NOTHING;
