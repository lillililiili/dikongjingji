-- R6-3 相关内容（docs/redesign/06-原型对齐修正计划.md §3，契约 contracts/openapi-content.yaml 1.1.0）：
-- 商品详情按“内容中提到”反查提到这件商品的图文。只加索引，不改表。
CREATE INDEX idx_content_article_ref_target ON content_article_ref (ref_type, ref_id);
