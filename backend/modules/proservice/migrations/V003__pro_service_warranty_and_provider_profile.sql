-- S4-4（docs/redesign/03-App实现任务计划.md §28；契约 contracts/openapi-proservice.yaml 1.2.0、openapi-ops-proservice.yaml 1.1.0）：
-- 1. 服务的质保期（如“维修后质保 90 天”），运营端填写，App 设备维护详情在“保修与收费边界”里显示。可以为空。
-- 2. 服务商主页资料（原型 ProviderPage：经营主体、服务方式、授权有效期、可交付清单、本地团队标签）：服务商在工作台提交，
--    平台运营在运营端审核。published_* 是主页上正在显示的（最近一次审核通过的），submitted_* 是最近提交的一份；
--    从没通过过时 published_* 为空。可交付清单换行分隔（服务校验条数和每条长度）。
-- 3. 审核和提交的记录，只追加（运营端详情里显示）。

ALTER TABLE pro_service ADD COLUMN warranty_text varchar(40);

CREATE TABLE pro_provider_profile (
    provider_id uuid PRIMARY KEY REFERENCES identity_organization(id) ON DELETE RESTRICT,
    -- PENDING 待审核；APPROVED 最近一份已通过；REJECTED 最近一份被驳回。
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    submitted_legal_entity varchar(60) NOT NULL,
    submitted_service_modes varchar(40) NOT NULL,
    submitted_authorization_until date,
    submitted_deliverables varchar(120) NOT NULL DEFAULT '',
    submitted_local_team_label varchar(12),
    submitted_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    submitted_at timestamptz NOT NULL,
    published_legal_entity varchar(60),
    published_service_modes varchar(40),
    published_authorization_until date,
    published_deliverables varchar(120),
    published_local_team_label varchar(12),
    reject_reason varchar(200),
    reviewed_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    reviewed_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_pro_provider_profile_published CHECK (
        (published_legal_entity IS NULL) = (published_service_modes IS NULL)
        AND (published_legal_entity IS NULL) = (published_deliverables IS NULL)
    ),
    CONSTRAINT ck_pro_provider_profile_approved CHECK (status <> 'APPROVED' OR published_legal_entity IS NOT NULL),
    CONSTRAINT ck_pro_provider_profile_rejected CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL)),
    CONSTRAINT ck_pro_provider_profile_reviewed CHECK ((reviewed_at IS NULL) = (reviewed_by IS NULL))
);

CREATE INDEX idx_pro_provider_profile_queue ON pro_provider_profile (status, submitted_at, provider_id);

CREATE TABLE pro_provider_profile_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id uuid NOT NULL REFERENCES pro_provider_profile(provider_id) ON DELETE CASCADE,
    action varchar(16) NOT NULL CHECK (action IN ('SUBMIT', 'APPROVE', 'REJECT')),
    summary varchar(300) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX idx_pro_provider_profile_change ON pro_provider_profile_change (provider_id, changed_at DESC, id DESC);
