-- R6-2 先体验（docs/redesign/06-原型对齐修正计划.md §3）：线上体验项目和馆内设备可以关联一件商城商品，
-- 商品详情据此显示“先体验，再决定”（契约 contracts/openapi-experience.yaml 1.2.0）。
-- 商品属于商城模块：只存商品 ID，不加外键，本模块不读商城的表。为空表示没有关联。

ALTER TABLE experience_item ADD COLUMN product_id uuid;

ALTER TABLE venue_device ADD COLUMN product_id uuid;

CREATE INDEX idx_experience_item_product ON experience_item (product_id, status, sort_order, id)
    WHERE product_id IS NOT NULL;

CREATE INDEX idx_venue_device_product ON venue_device (product_id, status, venue_id)
    WHERE product_id IS NOT NULL;
