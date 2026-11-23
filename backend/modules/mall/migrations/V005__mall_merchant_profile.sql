-- S4-4 商城交易字段与 S5-2 商家主页（docs/redesign/08-后续开发计划.md §5 S4-4、A1；契约 contracts/openapi-mall-catalog.yaml 1.4.0、
-- contracts/openapi-mall-merchant.yaml 1.0.0、contracts/openapi-ops-merchant-profile.yaml 1.0.0）。只加不改已有数据。

-- 商品可以标“不支持七天无理由”（比如定制、拆封后影响二次销售的）。默认支持：平台规则是未拆封七天无理由。
ALTER TABLE mall_product
    ADD COLUMN no_reason_return boolean NOT NULL DEFAULT true;

-- 供给上的开票方、售后责任方（比如授权经销商卖的货由厂家保修）。为空时取商家资料里的默认，再为空时就是商家自己。
ALTER TABLE mall_offer
    ADD COLUMN invoice_issuer varchar(60),
    ADD COLUMN aftersale_party varchar(60);

-- 商家资料：商家在工作台填、提交，平台运营在运营端审核（照机构资质的做法）。对外只展示审核通过的版本（本表）；
-- 提交的每一版在 mall_merchant_profile_submission 里，审核通过时整份复制到这里。
-- 经营主体、简介、可售说明、授权有效期、品类，以及给供给兜底的开票方、售后责任方。
CREATE TABLE mall_merchant_profile (
    organization_id uuid PRIMARY KEY REFERENCES identity_organization(id) ON DELETE RESTRICT,
    business_entity varchar(160) NOT NULL,
    intro varchar(300),
    sale_scope_note varchar(40),
    authorization_until date,
    category_note varchar(40),
    invoice_issuer varchar(60),
    aftersale_party varchar(60),
    submission_id uuid NOT NULL,
    approved_at timestamptz NOT NULL,
    approved_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- status：SUBMITTED 待审核；APPROVED 已通过；REJECTED 已驳回（写原因）；SUPERSEDED 待审核时商家又交了新的一版。
CREATE TABLE mall_merchant_profile_submission (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    business_entity varchar(160) NOT NULL,
    intro varchar(300),
    sale_scope_note varchar(40),
    authorization_until date,
    category_note varchar(40),
    invoice_issuer varchar(60),
    aftersale_party varchar(60),
    status varchar(16) NOT NULL CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    submitted_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    submitted_by_name varchar(80) NOT NULL,
    submitted_at timestamptz NOT NULL,
    reviewed_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    reviewed_by_name varchar(80),
    reviewed_at timestamptz,
    reject_reason varchar(200),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_mall_merchant_submission_review
        CHECK ((status IN ('APPROVED', 'REJECTED')) = (reviewed_at IS NOT NULL)
               AND (reviewed_at IS NULL) = (reviewed_by IS NULL)),
    CONSTRAINT ck_mall_merchant_submission_reject
        CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))
);

-- 一家同时只有一版待审核。
CREATE UNIQUE INDEX uq_mall_merchant_submission_pending
    ON mall_merchant_profile_submission (organization_id)
    WHERE status = 'SUBMITTED';
CREATE INDEX idx_mall_merchant_submission_org
    ON mall_merchant_profile_submission (organization_id, submitted_at DESC, id DESC);
CREATE INDEX idx_mall_merchant_submission_status
    ON mall_merchant_profile_submission (status, submitted_at DESC, id DESC);
