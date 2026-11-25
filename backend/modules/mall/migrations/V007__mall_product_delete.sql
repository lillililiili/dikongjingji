-- 商家删除自己的草稿商品（2026-11-25，契约 contracts/openapi-merchant-catalog.yaml 1.1.0、openapi-ops-catalog.yaml 1.3.0）。
-- 删除是把状态改成 DELETED（V001 就有这个状态，各处读取都当作不存在），资料和操作记录都留着；操作记录多一种动作。
-- 只放宽约束，不改已有数据。

ALTER TABLE mall_catalog_operation DROP CONSTRAINT mall_catalog_operation_action_check;
ALTER TABLE mall_catalog_operation ADD CONSTRAINT mall_catalog_operation_action_check CHECK (action IN (
    'PRODUCT_CREATE', 'PRODUCT_UPDATE', 'PRODUCT_PUBLISH', 'PRODUCT_UNPUBLISH', 'PRODUCT_DELETE', 'SKU_CREATE',
    'SKU_UPDATE', 'OFFER_CREATE', 'OFFER_UPDATE', 'OFFER_PRICE_CHANGE', 'OFFER_ACTIVATE', 'OFFER_DEACTIVATE',
    'IMPORT'));
