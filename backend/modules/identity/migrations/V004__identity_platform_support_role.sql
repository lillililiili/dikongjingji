-- G1 客服会话（docs/redesign/03-App实现任务计划.md §26.1）：平台客服角色。
-- 只用来读和回复“平台客服”的会话，不带任何巡检权限；和平台运营（PLATFORM_OPERATOR，能跨租户读巡检、导出对账）分开，
-- 客服账号拿不到运营的权限。全局角色，不限定机构。
INSERT INTO identity_role (role_code, role_name, scope_type)
VALUES ('PLATFORM_SUPPORT', '平台客服', 'GLOBAL')
ON CONFLICT (role_code) DO NOTHING;
