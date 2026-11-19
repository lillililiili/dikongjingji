-- 仅开发环境：由 @Profile("dev") 的 inspectionDevelopmentDataFlyway 加载，生产不会执行。
-- F2 四类专业服务的示例服务商（名称沿用 2026-11-14 修订版原型的服务详情画板和 ProviderPage 画板）：
-- 鹰眼智能巡检（航拍）、示例测绘服务商（测绘）、示例机器人商家（设备维护、技术支持）。
-- 服务商资料归巡检模块：有了在用的资料，服务商主页才能打开，需求（F1）的平台匹配和邀请才能找到它们。
-- “示例机器人商家”的机构由商城开发数据建；这里用同一个 ID 和编码先建一次，谁先执行都一样。

INSERT INTO identity_organization (id, organization_code, organization_name, organization_type, status)
VALUES
    ('f2000000-0000-4000-8000-000000000001', 'DEV-PROVIDER-EAGLE', '鹰眼智能巡检', 'PROVIDER', 'ACTIVE'),
    ('f2000000-0000-4000-8000-000000000002', 'DEV-PROVIDER-SURVEY', '示例测绘服务商', 'PROVIDER', 'ACTIVE'),
    ('c1000000-0000-4000-8000-000000000001', 'DEV-MALL-ROBOT-MAKER', '示例机器人商家', 'PROVIDER', 'ACTIVE')
ON CONFLICT (id) DO UPDATE
SET organization_code = EXCLUDED.organization_code,
    organization_name = EXCLUDED.organization_name,
    organization_type = EXCLUDED.organization_type,
    status = EXCLUDED.status,
    updated_at = now();

INSERT INTO inspection_provider_profile (provider_id, logo_asset_code, certification_label, team_description, status)
VALUES
    ('f2000000-0000-4000-8000-000000000001', 'PROVIDER_INITIAL', '资质已审核', '东营本地团队，无人机航拍与巡检', 'ACTIVE'),
    ('f2000000-0000-4000-8000-000000000002', 'PROVIDER_INITIAL', '资质已审核', '航测外业与三维建模团队', 'ACTIVE'),
    ('c1000000-0000-4000-8000-000000000001', 'PROVIDER_INITIAL', '资质已审核', '无人机与机器人维护、技术支持团队', 'ACTIVE')
ON CONFLICT (provider_id) DO UPDATE
SET logo_url = NULL,
    logo_asset_code = EXCLUDED.logo_asset_code,
    certification_label = EXCLUDED.certification_label,
    team_description = EXCLUDED.team_description,
    status = EXCLUDED.status,
    updated_at = now();
