-- 仅开发环境：由 @Profile("dev") 的 identityOperatorDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- 运营端（S3-1，apps/admin）开发用的平台运营账号 dev-operator，所属机构是示例平台机构（R__dev_identity_accounts.sql 建的）。
-- 和其他示例账号不同：平台运营能跨租户读巡检、管机构和资质，所以口令不写进仓库。启动后端前设环境变量
-- DEV_OPERATOR_PASSWORD（至少 12 位）才建；没设或太短时占位符是空串，下面每条语句都不写任何东西。
-- 只有平台运营一个角色，不带平台客服；处理反馈用 dev-support。

INSERT INTO identity_user (id, login_name, display_name, password_hash, default_organization_id)
SELECT '90000000-0000-4000-8000-0000000000b1',
       'dev-operator',
       '平台运营（示例）',
       crypt('${devOperatorPassword}', gen_salt('bf', 10)),
       '90000000-0000-4000-8000-000000000001'
WHERE length('${devOperatorPassword}') >= 12
ON CONFLICT (id) DO UPDATE
SET login_name = EXCLUDED.login_name,
    display_name = EXCLUDED.display_name,
    password_hash = EXCLUDED.password_hash,
    default_organization_id = EXCLUDED.default_organization_id,
    status = 'ACTIVE';

INSERT INTO identity_organization_member (organization_id, user_id)
SELECT '90000000-0000-4000-8000-000000000001', '90000000-0000-4000-8000-0000000000b1'
WHERE length('${devOperatorPassword}') >= 12
ON CONFLICT (organization_id, user_id) DO UPDATE
SET member_status = 'ACTIVE';

INSERT INTO identity_user_role (user_id, role_id, organization_id)
SELECT '90000000-0000-4000-8000-0000000000b1', id, NULL
FROM identity_role
WHERE role_code = 'PLATFORM_OPERATOR'
  AND length('${devOperatorPassword}') >= 12
ON CONFLICT DO NOTHING;
