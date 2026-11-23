-- 仅开发环境：由 @Profile("dev") 的 tradeDevelopmentDataFlyway 加载，生产不会执行。
-- S4-2 商品售后、S5-2 商家主页（docs/redesign/09-商品售后与商家主页.md）的示例数据，都给“示例机器人商家”：
--   · 默认寄回地址（售后同意时不另填就用它，超时自动同意也用它）；
--   · 一版审核通过的商家资料（商家主页的经营主体、简介、可售说明、授权有效期、品类，以及给供给兜底的开票方、售后责任方）；
--   · “桌面六轴机械臂 教学版”标为不支持七天无理由；示例教育装备代理卖的编程机器人由厂家保修（供给上的售后责任方）。
-- 文件名排在 R__dev_trade_merchant_operator.sql 之后：要用它建的 dev-merchant 账号（c1…a1）作操作人和审核人。
-- 地址、电话、主体名称都是示例，不是真实信息。

INSERT INTO trade_merchant_return_address (organization_id, contact_name, phone, address, note, updated_by)
VALUES ('c1000000-0000-4000-8000-000000000001', '示例机器人商家 售后组', '0546-0000000',
        '山东省东营市东营区 示例路 120 号（示例）', '包裹内请附订单号', 'c1000000-0000-4000-8000-0000000000a1')
ON CONFLICT (organization_id) DO UPDATE
SET contact_name = EXCLUDED.contact_name,
    phone = EXCLUDED.phone,
    address = EXCLUDED.address,
    note = EXCLUDED.note,
    updated_at = now();

INSERT INTO mall_merchant_profile_submission (
    id, organization_id, business_entity, intro, sale_scope_note, authorization_until, category_note, invoice_issuer,
    aftersale_party, status, submitted_by, submitted_by_name, submitted_at, reviewed_by, reviewed_by_name, reviewed_at
)
VALUES ('c6000000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001', '示例机器人科技有限公司（示例）',
        '教育机器人厂家直营，东营现货，配套数字课程。', '全国发货', DATE '2027-10-31', '教育机器人', '示例机器人商家',
        '示例机器人商家 · 厂家保修', 'APPROVED', 'c1000000-0000-4000-8000-0000000000a1', '示例机器人商家操作员',
        now() - interval '3 days', 'c1000000-0000-4000-8000-0000000000a1', '平台运营（示例）', now() - interval '2 days')
ON CONFLICT (id) DO UPDATE
SET business_entity = EXCLUDED.business_entity,
    intro = EXCLUDED.intro,
    sale_scope_note = EXCLUDED.sale_scope_note,
    authorization_until = EXCLUDED.authorization_until,
    category_note = EXCLUDED.category_note,
    invoice_issuer = EXCLUDED.invoice_issuer,
    aftersale_party = EXCLUDED.aftersale_party,
    updated_at = now();

INSERT INTO mall_merchant_profile (
    organization_id, business_entity, intro, sale_scope_note, authorization_until, category_note, invoice_issuer,
    aftersale_party, submission_id, approved_at, approved_by
)
SELECT organization_id, business_entity, intro, sale_scope_note, authorization_until, category_note, invoice_issuer,
       aftersale_party, id, reviewed_at, reviewed_by
FROM mall_merchant_profile_submission
WHERE id = 'c6000000-0000-4000-8000-000000000001'
ON CONFLICT (organization_id) DO UPDATE
SET business_entity = EXCLUDED.business_entity,
    intro = EXCLUDED.intro,
    sale_scope_note = EXCLUDED.sale_scope_note,
    authorization_until = EXCLUDED.authorization_until,
    category_note = EXCLUDED.category_note,
    invoice_issuer = EXCLUDED.invoice_issuer,
    aftersale_party = EXCLUDED.aftersale_party,
    submission_id = EXCLUDED.submission_id,
    updated_at = now();

UPDATE mall_product SET no_reason_return = false
WHERE id = 'c2000000-0000-4000-8000-000000000102';

UPDATE mall_offer offer
SET aftersale_party = '示例机器人商家 · 厂家保修'
FROM mall_sku sku
WHERE sku.id = offer.sku_id
  AND sku.product_id = 'c2000000-0000-4000-8000-000000000101'
  AND offer.merchant_id = 'c1000000-0000-4000-8000-000000000002';
