-- S6-01（docs/delivery/问题关闭记录.md；契约 contracts/openapi-ops-organization.yaml 1.1.0）：运营端“机构与资质”给经营机构
-- “开通服务商资料”。服务商资料本身在巡检模块（巡检迁移 V004 的 inspection_provider_profile），由启动模块经端口去建；
-- 身份模块这里只把这一步记进操作记录，所以操作记录的动作多一个 PROVIDER_PROFILE_OPEN。
-- 只加不改：原有的动作都保留，已有记录不动。

ALTER TABLE identity_organization_operation DROP CONSTRAINT identity_organization_operation_action_check;
ALTER TABLE identity_organization_operation ADD CONSTRAINT identity_organization_operation_action_check
    CHECK (action IN (
        'CREATE', 'QUALIFICATION_SUBMIT', 'QUALIFICATION_APPROVE', 'QUALIFICATION_REJECT',
        'ATTACHMENT_ADD', 'ATTACHMENT_REMOVE', 'ACCOUNT_CREATE', 'ROLES_CHANGE', 'PROVIDER_PROFILE_OPEN'));
