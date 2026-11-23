-- S3-1 运营端“机构入驻与资质审核”（docs/redesign/08-后续开发计划.md §2 S3-1，契约 contracts/openapi-ops-organization.yaml 1.0.0）。
-- 平台运营建机构、录资质资料、上传资质附件、审核通过或驳回、给机构建账号并授机构角色。审核通过写
-- identity_organization.qualification_reviewed_at（V005，App 上的“资质已审核”只看它），驳回把它清空。
-- 每一步都在 identity_organization_operation 里留一条（谁、什么时候、做了什么），只追加。只加不改已有数据。

-- 机构所在城市（平台城市目录的 city_code；城市目录在巡检模块，身份迁移先执行，所以不加外键，由运营端经端口校验）。
-- ops_create_key 是运营端新建机构的幂等键：同一个键重复提交只建一家。
ALTER TABLE identity_organization
    ADD COLUMN city_code varchar(16),
    ADD COLUMN ops_create_key uuid;

CREATE UNIQUE INDEX uq_identity_organization_ops_create_key
    ON identity_organization (ops_create_key)
    WHERE ops_create_key IS NOT NULL;

CREATE INDEX idx_identity_organization_type_created
    ON identity_organization (organization_type, created_at DESC, id DESC);

-- 资质资料：只存文字（主体名称、统一社会信用代码、经营范围、行业资质说明），附件另存元数据。一家机构一份，
-- 改了资料要重新审核。status：SUBMITTED 待审核、APPROVED 已通过、REJECTED 已驳回（写驳回原因）。
CREATE TABLE identity_organization_qualification (
    organization_id uuid PRIMARY KEY REFERENCES identity_organization(id) ON DELETE RESTRICT,
    subject_name varchar(160) NOT NULL,
    license_number varchar(18) NOT NULL CHECK (license_number ~ '^[0-9A-HJ-NPQRTUWXY]{18}$'),
    business_scope varchar(500),
    qualification_note varchar(500),
    status varchar(16) NOT NULL CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED')),
    submitted_at timestamptz NOT NULL,
    submitted_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    reviewed_at timestamptz,
    reviewed_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    reject_reason varchar(200),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_identity_qualification_review
        CHECK ((status = 'SUBMITTED') = (reviewed_at IS NULL) AND (reviewed_at IS NULL) = (reviewed_by IS NULL)),
    CONSTRAINT ck_identity_qualification_reject
        CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))
);

CREATE INDEX idx_identity_qualification_status
    ON identity_organization_qualification (status, submitted_at DESC);

-- 资质附件的元数据：文件本身在对象存储（开发环境是本机文件，见 bootstrap 的 LocalQualificationFileStore），
-- 这里只存名称、类型、大小、摘要和存储键；不对外给存储地址，只经受权接口读。移除只做标记，记录保留。
CREATE TABLE identity_organization_attachment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    file_name varchar(120) NOT NULL,
    content_type varchar(32) NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    sha256 char(64) NOT NULL,
    storage_key varchar(200) NOT NULL UNIQUE,
    uploaded_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    uploaded_at timestamptz NOT NULL,
    removed_at timestamptz,
    removed_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    CONSTRAINT ck_identity_attachment_removed CHECK ((removed_at IS NULL) = (removed_by IS NULL))
);

CREATE INDEX idx_identity_attachment_organization
    ON identity_organization_attachment (organization_id, uploaded_at DESC)
    WHERE removed_at IS NULL;

-- 运营端对机构做过的每一步（留痕）。detail 是给人看的说明（驳回原因、授了哪些角色等），不含口令和联系方式。
CREATE TABLE identity_organization_operation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    action varchar(32) NOT NULL CHECK (action IN (
        'CREATE', 'QUALIFICATION_SUBMIT', 'QUALIFICATION_APPROVE', 'QUALIFICATION_REJECT',
        'ATTACHMENT_ADD', 'ATTACHMENT_REMOVE', 'ACCOUNT_CREATE', 'ROLES_CHANGE')),
    detail varchar(500) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX idx_identity_operation_organization
    ON identity_organization_operation (organization_id, occurred_at DESC, id DESC);
