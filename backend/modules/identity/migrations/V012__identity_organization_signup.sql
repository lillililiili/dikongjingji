-- 商家自助入驻（2026-11-24，契约 contracts/openapi-organization-signup.yaml 1.0.0）。
-- 商家在 Web 工作台登录页“申请入驻”：填机构、资质和联系人手机号（短信验证码），服务端建经营机构（PROVIDER）、
-- 待审核的资质资料、一个服务商操作员账号（登录名和口令由申请人自己设）。之后商家登录工作台补资质附件，
-- 平台运营仍在“机构与资质”里审核。机构、资质、账号、角色都沿用 V007 的表，这里只放开两个 CHECK：
-- 1. 短信验证码多一个用途 SIGNUP（申请入驻时验证联系人手机号）；
-- 2. 机构操作记录多一个动作 SIGNUP（申请入驻那一步；之后的资料修改、附件上传沿用原有动作，操作人是商家自己的账号）。
-- 申请的幂等键沿用 identity_organization.ops_create_key（同一个键只建一家）。只改约束，不改已有数据。

ALTER TABLE identity_sms_code DROP CONSTRAINT identity_sms_code_purpose_check;
ALTER TABLE identity_sms_code ADD CONSTRAINT identity_sms_code_purpose_check
    CHECK (purpose IN ('LOGIN', 'VERIFY_CURRENT', 'BIND_NEW', 'EXPORT', 'CHANGE_TICKET', 'SIGNUP'));

ALTER TABLE identity_organization_operation DROP CONSTRAINT identity_organization_operation_action_check;
ALTER TABLE identity_organization_operation ADD CONSTRAINT identity_organization_operation_action_check
    CHECK (action IN (
        'CREATE', 'QUALIFICATION_SUBMIT', 'QUALIFICATION_APPROVE', 'QUALIFICATION_REJECT',
        'ATTACHMENT_ADD', 'ATTACHMENT_REMOVE', 'ACCOUNT_CREATE', 'ROLES_CHANGE', 'PROVIDER_PROFILE_OPEN', 'SIGNUP'));
