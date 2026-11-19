-- 仅开发环境：由 @Profile("dev") 的 tradeDevelopmentDataFlyway 加载，生产不会执行。
-- dev-merchant：商城示例商家“示例机器人商家”的操作员，用来通过受权接口发货、核销自提、退款
-- （infra/scripts/dev-merchant-order.cjs）。这家的编程机器人套装既能快递也能到体验馆自提，两种都能试。
-- 密码和身份种子 R__dev_identity_accounts.sql 里的开发账号一样，是公开写在仓库里的，dev 环境只能用在本机或内网。
-- 机构由商城开发种子建，所以这份排在商城开发数据之后。

INSERT INTO identity_user (id, login_name, display_name, password_hash, default_organization_id)
VALUES (
    'c1000000-0000-4000-8000-0000000000a1',
    'dev-merchant',
    '示例机器人商家操作员',
    crypt('test-password', gen_salt('bf', 10)),
    'c1000000-0000-4000-8000-000000000001'
)
ON CONFLICT (id) DO UPDATE
SET login_name = EXCLUDED.login_name,
    display_name = EXCLUDED.display_name,
    password_hash = EXCLUDED.password_hash,
    default_organization_id = EXCLUDED.default_organization_id,
    status = 'ACTIVE';

INSERT INTO identity_organization_member (organization_id, user_id)
VALUES ('c1000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-0000000000a1')
ON CONFLICT (organization_id, user_id) DO UPDATE
SET member_status = 'ACTIVE';

INSERT INTO identity_user_role (user_id, role_id, organization_id)
SELECT 'c1000000-0000-4000-8000-0000000000a1', id, 'c1000000-0000-4000-8000-000000000001'
FROM identity_role WHERE role_code = 'PROVIDER_OPERATOR'
ON CONFLICT DO NOTHING;
