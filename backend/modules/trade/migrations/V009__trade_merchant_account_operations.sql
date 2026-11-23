-- S3-1 运营端“商品与供给”的收款账户登记（契约 contracts/openapi-ops-catalog.yaml 1.0.0）。
-- 平台运营在运营端登记或修改商家（二级商户）的微信收款账户，或经批量导入写入；每次都在这里留一条（谁、什么时候、
-- 从什么改成什么），只追加。账户本身仍是 V001 的 trade_merchant_account，不改已有数据。只登记，不做进件。
CREATE TABLE trade_merchant_account_operation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    action varchar(16) NOT NULL CHECK (action IN ('REGISTER', 'CHANGE', 'IMPORT')),
    detail varchar(500) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX idx_trade_merchant_account_operation_org
    ON trade_merchant_account_operation (organization_id, occurred_at DESC, id DESC);
