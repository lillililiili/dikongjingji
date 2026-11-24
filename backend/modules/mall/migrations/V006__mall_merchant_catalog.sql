-- 商家自助上架（2026-11-24，契约 contracts/openapi-merchant-catalog.yaml 1.0.0、openapi-ops-catalog.yaml 1.1.0）。
-- 商家（服务商操作员）在 Web 工作台“我的商品”里自己建商品、改资料、管规格和本家的供给、上下架；规则和运营端一样，
-- 另加：只能看、改自己建的商品，供给的商家只能是自己，资质审核通过前不能上架。平台运营在“商品与供给”里仍能看到、
-- 下架所有商品。只加一列，不改已有数据：老商品是平台建的，owner_organization_id 为空。

ALTER TABLE mall_product
    ADD COLUMN owner_organization_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT;

CREATE INDEX idx_mall_product_owner
    ON mall_product (owner_organization_id, updated_at DESC, id DESC)
    WHERE owner_organization_id IS NOT NULL;
