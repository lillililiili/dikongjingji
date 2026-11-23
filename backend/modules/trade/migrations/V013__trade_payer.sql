-- S5-7（交易 V013）企业身份下单记付款主体（docs/redesign/08-后续开发计划.md §5“企业组织”：企业身份下单只记付款主体，不做企业结算；
-- 契约 contracts/openapi-trade.yaml 0.10.0）。下单时由身份模块经端口给出买家当时的身份，交易只存快照：
-- PERSONAL 个人（payer_name 为空，页面显示“个人”），ENTERPRISE 企业（记企业机构 ID 和当时的名称）。
-- 已有订单不回填：payer_type 为空当作个人。钱仍进商家自己的子商户号，付款人仍是买家账号本人。
ALTER TABLE trade_order
    ADD COLUMN payer_type varchar(16) CHECK (payer_type IN ('PERSONAL', 'ENTERPRISE')),
    ADD COLUMN payer_organization_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    ADD COLUMN payer_name varchar(160),
    ADD CONSTRAINT ck_trade_order_payer
        CHECK ((payer_type = 'ENTERPRISE') = (payer_organization_id IS NOT NULL AND payer_name IS NOT NULL));

-- 企业的订单列表（企业成员查看本企业付款的订单）。
CREATE INDEX idx_trade_order_payer_organization
    ON trade_order (payer_organization_id, created_at DESC, id DESC)
    WHERE payer_organization_id IS NOT NULL;
