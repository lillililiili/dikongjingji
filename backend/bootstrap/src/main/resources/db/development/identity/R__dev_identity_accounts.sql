-- 仅开发环境：由 @Profile("dev") 的 identityDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- 两个示例账号，给本地手工验收和联调用（账号结构、密码与 infra/scripts/verify-f04-reservation-smoke.ps1 一致）：
--   dev-customer  客户，用来预约、确认方案、验收
--   dev-provider  “云翼科技”的服务商操作员，用来通过受权接口发布方案、推进履约、发布交付
-- 两个账号的密码都是：test-password
-- 密码是公开写在仓库里的，所以 dev 环境配置只能用在本机或内网，任何外面能访问到的环境都不要开 dev。
-- 不建平台运营账号。

INSERT INTO identity_organization (id, organization_code, organization_name, organization_type)
VALUES ('11111111-1111-1111-1111-aaaaaaaaaaaa', 'DEV-CUSTOMER', '东营示例物流公司', 'CUSTOMER')
ON CONFLICT (id) DO UPDATE
SET organization_name = EXCLUDED.organization_name;

-- 巡检开发种子里的服务商；那份种子负责它的名称和资料，这里只保证它存在。
INSERT INTO identity_organization (id, organization_code, organization_name, organization_type)
VALUES ('22222222-2222-2222-2222-222222222222', 'DEV-PROVIDER-YUNYI', '云翼科技', 'PROVIDER')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity_user (id, login_name, display_name, password_hash, default_organization_id)
VALUES
    (
        '11111111-1111-1111-1111-111111111111',
        'dev-customer',
        '王女士（示例）',
        crypt('test-password', gen_salt('bf', 10)),
        '11111111-1111-1111-1111-aaaaaaaaaaaa'
    ),
    (
        '22222222-2222-2222-2222-333333333333',
        'dev-provider',
        '云翼科技操作员',
        crypt('test-password', gen_salt('bf', 10)),
        '22222222-2222-2222-2222-222222222222'
    )
ON CONFLICT (id) DO UPDATE
SET login_name = EXCLUDED.login_name,
    display_name = EXCLUDED.display_name,
    password_hash = EXCLUDED.password_hash,
    default_organization_id = EXCLUDED.default_organization_id,
    status = 'ACTIVE';

INSERT INTO identity_organization_member (organization_id, user_id)
VALUES
    ('11111111-1111-1111-1111-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111'),
    ('22222222-2222-2222-2222-222222222222', '22222222-2222-2222-2222-333333333333')
ON CONFLICT (organization_id, user_id) DO UPDATE
SET member_status = 'ACTIVE';

INSERT INTO identity_user_role (user_id, role_id, organization_id)
SELECT '11111111-1111-1111-1111-111111111111', id, NULL
FROM identity_role WHERE role_code = 'CUSTOMER'
ON CONFLICT DO NOTHING;

INSERT INTO identity_user_role (user_id, role_id, organization_id)
SELECT '22222222-2222-2222-2222-333333333333', id, '22222222-2222-2222-2222-222222222222'
FROM identity_role WHERE role_code = 'PROVIDER_OPERATOR'
ON CONFLICT DO NOTHING;
