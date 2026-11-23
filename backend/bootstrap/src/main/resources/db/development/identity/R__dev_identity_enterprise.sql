-- 仅开发环境（identityDevelopmentDataFlyway 加载，生产不会执行）：S5-7 企业组织、S5-8 手机号的示例数据。
-- dev-customer 绑定示例手机号 13800002026（页面显示 138****2026，与原型一致；开发环境的短信是本机模拟服务，不会发出）。
-- 示例企业“东营港务物流（示例）”，统一社会信用代码是占位的 91370500EXAMPLE26X（格式合法，不是真实企业），dev-customer 是管理员。
-- 当前身份不动（保持个人），已有 E2E 的下单都按个人付款。只补不删；号码已被别的开发账号绑定时不改。

UPDATE identity_user
SET mobile = '13800002026'
WHERE id = '11111111-1111-1111-1111-111111111111'
  AND mobile IS NULL
  AND NOT EXISTS (SELECT 1 FROM identity_user other WHERE other.mobile = '13800002026');

INSERT INTO identity_organization (id, organization_code, organization_name, organization_type, city_code)
VALUES ('e5000000-0000-4000-8000-000000000001', 'DEV-ENT-PORT', '东营港务物流（示例）', 'CUSTOMER', '370500')
ON CONFLICT (id) DO NOTHING;

INSERT INTO identity_enterprise (organization_id, credit_code, created_by, create_key)
VALUES ('e5000000-0000-4000-8000-000000000001', '91370500EXAMPLE26X', '11111111-1111-1111-1111-111111111111',
        'e5000000-0000-4000-8000-0000000000c1')
ON CONFLICT (organization_id) DO NOTHING;

INSERT INTO identity_enterprise_member (organization_id, user_id, member_role, joined_at)
SELECT 'e5000000-0000-4000-8000-000000000001', '11111111-1111-1111-1111-111111111111', 'ADMIN', now()
WHERE NOT EXISTS (
    SELECT 1 FROM identity_enterprise_member
    WHERE organization_id = 'e5000000-0000-4000-8000-000000000001'
      AND user_id = '11111111-1111-1111-1111-111111111111'
);
