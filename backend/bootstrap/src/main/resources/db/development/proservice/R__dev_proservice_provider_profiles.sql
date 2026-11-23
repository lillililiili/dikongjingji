-- 仅开发环境：由 @Profile("dev") 的 proserviceDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- S4-4（专业服务 V003）的示例数据：
-- 鹰眼智能巡检的服务商主页资料，取自原型 ProviderPage（经营主体、服务方式、授权有效期、可交付、东营本地团队），
-- 状态是已通过：App 的服务商主页直接显示。提交人记的是 dev-provider 账号（示例机构没有自己的操作员账号）。
-- 云翼科技（dev-provider 的机构）故意不放资料，用来在工作台走一遍“提交 → 运营审核”。
-- 设备维护示例服务的质保期写在 R__dev_proservice_catalog.sql 末尾（那份会先删再插服务）。可重复执行。

DELETE FROM pro_provider_profile WHERE provider_id = 'f2000000-0000-4000-8000-000000000001';

INSERT INTO pro_provider_profile (
    provider_id, status, submitted_legal_entity, submitted_service_modes, submitted_authorization_until,
    submitted_deliverables, submitted_local_team_label, submitted_by, submitted_at, published_legal_entity,
    published_service_modes, published_authorization_until, published_deliverables, published_local_team_label,
    reviewed_by, reviewed_at
)
VALUES (
    'f2000000-0000-4000-8000-000000000001', 'APPROVED', '示例鹰眼智能科技有限公司', '无人机巡检 · 机器狗夜巡',
    DATE '2027-10-31', E'巡检报告\n隐患清单\n影像存档\n隐患跟踪', '东营本地团队',
    '22222222-2222-2222-2222-333333333333', now(), '示例鹰眼智能科技有限公司', '无人机巡检 · 机器狗夜巡',
    DATE '2027-10-31', E'巡检报告\n隐患清单\n影像存档\n隐患跟踪', '东营本地团队',
    '22222222-2222-2222-2222-333333333333', now()
);
