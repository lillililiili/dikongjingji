-- S5-7 企业组织与成员（docs/redesign/08-后续开发计划.md §2 S5-7、§5“企业组织”默认决定；契约 contracts/openapi-identity-account.yaml 1.0.0）。
-- 客户账号自己创建企业（企业也是一家 CUSTOMER 类型的机构），邀请成员（手机号或邀请链接，7 天有效），成员角色只有管理员和成员两种；
-- 客户可以把“当前身份”切到自己加入的企业，之后下单时交易订单记下付款主体（交易迁移 V010），不做企业结算。
-- 只加不改：账号的默认机构、登录和授权不变（企业成员关系另存一张表，不进 identity_organization_member）。

-- 企业资料：一家企业一行，机构本身在 identity_organization（organization_type = 'CUSTOMER'）。
-- credit_code 是统一社会信用代码，18 位数字或大写字母（不含 I、O、Z、S、V），只校验格式；同一个代码只能建一家企业。
-- create_key 是创建时的幂等键：同一个账号同一个键重复提交只建一家。
CREATE TABLE identity_enterprise (
    organization_id uuid PRIMARY KEY REFERENCES identity_organization(id) ON DELETE RESTRICT,
    credit_code varchar(18) NOT NULL CHECK (credit_code ~ '^[0-9A-HJ-NPQRTUWXY]{18}$'),
    created_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    create_key uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISSOLVED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_identity_enterprise_credit_code
    ON identity_enterprise (credit_code)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_identity_enterprise_create_key
    ON identity_enterprise (created_by, create_key);

-- 企业成员：ADMIN 管理员（管理成员和角色）、MEMBER 成员（以企业身份下单、查看企业订单、邀请成员）。
-- 状态 ACTIVE 在企业里；LEFT 自己退出；REMOVED 被管理员移除。退出或移除后再加入是新的一行，旧行保留作记录。
CREATE TABLE identity_enterprise_member (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES identity_enterprise(organization_id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    member_role varchar(16) NOT NULL CHECK (member_role IN ('ADMIN', 'MEMBER')),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LEFT', 'REMOVED')),
    joined_at timestamptz NOT NULL,
    ended_at timestamptz,
    ended_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_identity_enterprise_member_ended
        CHECK ((status = 'ACTIVE') = (ended_at IS NULL))
);

CREATE UNIQUE INDEX uq_identity_enterprise_member_active
    ON identity_enterprise_member (organization_id, user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_identity_enterprise_member_user
    ON identity_enterprise_member (user_id, status);

-- 邀请：PHONE 发给一个手机号（对方用这个手机号登录后确认）；LINK 是邀请链接，拿到链接的登录客户都能确认，用一次就失效。
-- 链接里的邀请码只存摘要（code_hash），明文只在创建时返回一次。加入后的角色一律是成员，管理员由管理员调整。
-- 状态：PENDING 待确认（过了 expires_at 当作已过期）、ACCEPTED、DECLINED、REVOKED。
CREATE TABLE identity_enterprise_invitation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES identity_enterprise(organization_id) ON DELETE RESTRICT,
    invite_kind varchar(8) NOT NULL CHECK (invite_kind IN ('PHONE', 'LINK')),
    invitee_phone varchar(11) CHECK (invitee_phone ~ '^1[3-9][0-9]{9}$'),
    code_hash char(64),
    member_role varchar(16) NOT NULL DEFAULT 'MEMBER' CHECK (member_role IN ('MEMBER')),
    invited_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED')),
    sent_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    responded_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    responded_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_identity_invitation_kind
        CHECK ((invite_kind = 'PHONE') = (invitee_phone IS NOT NULL)
           AND (invite_kind = 'LINK') = (code_hash IS NOT NULL)),
    CONSTRAINT ck_identity_invitation_expiry CHECK (expires_at > sent_at),
    CONSTRAINT ck_identity_invitation_response
        CHECK ((status IN ('ACCEPTED', 'DECLINED')) = (responded_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_identity_invitation_code
    ON identity_enterprise_invitation (code_hash)
    WHERE code_hash IS NOT NULL;

-- 同一家企业对同一个手机号只能有一条待确认的邀请（再发一次是延长这一条）。
CREATE UNIQUE INDEX uq_identity_invitation_pending_phone
    ON identity_enterprise_invitation (organization_id, invitee_phone)
    WHERE status = 'PENDING' AND invitee_phone IS NOT NULL;

CREATE INDEX idx_identity_invitation_phone
    ON identity_enterprise_invitation (invitee_phone, status)
    WHERE invitee_phone IS NOT NULL;

CREATE INDEX idx_identity_invitation_organization
    ON identity_enterprise_invitation (organization_id, status, sent_at DESC);

-- 当前身份：NULL 是个人身份，否则是本人在其中的企业。整个账号一个值（换设备也一样），切换时重新签发令牌，令牌里带它（claim identity）。
-- 被移除或退出企业时清空。
ALTER TABLE identity_user
    ADD COLUMN acting_organization_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT;

-- 企业操作留痕沿用 identity_auth_audit（action_code 如 ENTERPRISE_CREATE、ENTERPRISE_INVITE、ENTERPRISE_MEMBER_REMOVE）。
CREATE INDEX idx_identity_auth_audit_organization_time
    ON identity_auth_audit (organization_id, occurred_at DESC)
    WHERE organization_id IS NOT NULL;
