CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE identity_user (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    login_name varchar(80) NOT NULL UNIQUE,
    display_name varchar(80) NOT NULL,
    mobile_ciphertext bytea,
    password_hash varchar(255) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    token_version integer NOT NULL DEFAULT 0 CHECK (token_version >= 0),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity_organization (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_code varchar(64) NOT NULL UNIQUE,
    organization_name varchar(160) NOT NULL,
    organization_type varchar(24) NOT NULL
        CHECK (organization_type IN ('CUSTOMER', 'PROVIDER', 'PLATFORM')),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity_organization_member (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    user_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    member_status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (member_status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organization_id, user_id)
);

CREATE TABLE identity_role (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    role_code varchar(80) NOT NULL UNIQUE,
    role_name varchar(120) NOT NULL,
    scope_type varchar(16) NOT NULL
        CHECK (scope_type IN ('GLOBAL', 'ORGANIZATION')),
    system_managed boolean NOT NULL DEFAULT true,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity_permission (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    permission_code varchar(120) NOT NULL UNIQUE,
    permission_name varchar(160) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity_role_permission (
    role_id uuid NOT NULL REFERENCES identity_role(id) ON DELETE RESTRICT,
    permission_id uuid NOT NULL REFERENCES identity_permission(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE identity_user_role (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    role_id uuid NOT NULL REFERENCES identity_role(id) ON DELETE RESTRICT,
    organization_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_until IS NULL OR valid_until > valid_from)
);

CREATE UNIQUE INDEX uk_identity_user_role_scope
    ON identity_user_role (
        user_id,
        role_id,
        COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid)
    );

CREATE INDEX idx_identity_org_member_user
    ON identity_organization_member (user_id, member_status);

CREATE INDEX idx_identity_user_role_active
    ON identity_user_role (user_id, valid_until);

CREATE TABLE identity_auth_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id uuid,
    action_code varchar(80) NOT NULL,
    target_type varchar(64),
    target_id uuid,
    organization_id uuid,
    request_id varchar(80) NOT NULL,
    result_code varchar(64) NOT NULL,
    reason_code varchar(120),
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_identity_auth_audit_actor_time
    ON identity_auth_audit (actor_user_id, occurred_at DESC);

INSERT INTO identity_permission (permission_code, permission_name)
VALUES
    ('inspection.reservation.create', '创建巡检预约'),
    ('inspection.reservation.read.own', '读取本人巡检预约'),
    ('inspection.reservation.read.assigned', '读取已分配巡检预约'),
    ('inspection.reservation.read.all', '跨租户读取巡检预约'),
    ('inspection.plan.publish.assigned', '发布已分配预约方案'),
    ('inspection.plan.read.own', '读取本人巡检方案'),
    ('inspection.plan.read.all', '跨租户读取巡检方案'),
    ('inspection.plan.confirm.own', '确认本人巡检方案'),
    ('inspection.order.read.own', '读取本人巡检服务单'),
    ('inspection.order.read.assigned', '读取已分配巡检服务单'),
    ('inspection.order.read.all', '跨租户读取巡检服务单'),
    ('inspection.order.arrange.assigned', '安排已分配巡检服务单'),
    ('inspection.order.progress.assigned', '更新已分配巡检进度'),
    ('inspection.delivery.publish.assigned', '发布已分配巡检交付'),
    ('inspection.delivery.download.own', '下载本人巡检交付'),
    ('inspection.delivery.download.all', '跨租户下载巡检交付'),
    ('inspection.acceptance.create.own', '验收本人巡检服务单'),
    ('inspection.audit.read', '读取巡检审计')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO identity_role (role_code, role_name, scope_type)
VALUES
    ('CUSTOMER', '客户', 'GLOBAL'),
    ('PROVIDER_OPERATOR', '服务商操作员', 'ORGANIZATION'),
    ('PLATFORM_OPERATOR', '平台运营', 'GLOBAL'),
    ('SYSTEM', '系统任务', 'GLOBAL')
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO identity_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM identity_role r
JOIN identity_permission p ON (
    (r.role_code = 'CUSTOMER' AND p.permission_code IN (
        'inspection.reservation.create',
        'inspection.reservation.read.own',
        'inspection.plan.read.own',
        'inspection.plan.confirm.own',
        'inspection.order.read.own',
        'inspection.delivery.download.own',
        'inspection.acceptance.create.own'
    ))
    OR
    (r.role_code = 'PROVIDER_OPERATOR' AND p.permission_code IN (
        'inspection.reservation.read.assigned',
        'inspection.plan.publish.assigned',
        'inspection.order.read.assigned',
        'inspection.order.arrange.assigned',
        'inspection.order.progress.assigned',
        'inspection.delivery.publish.assigned'
    ))
    OR
    (r.role_code = 'PLATFORM_OPERATOR' AND p.permission_code IN (
        'inspection.reservation.read.all',
        'inspection.plan.read.all',
        'inspection.order.read.all',
        'inspection.delivery.download.all',
        'inspection.audit.read'
    ))
)
ON CONFLICT (role_id, permission_id) DO NOTHING;
