-- 订单上的商家名称快照放宽到 160 个字符。
-- 它来自 identity_organization.organization_name（varchar(160)），V001 只给了 120：
-- 机构名超过 120 个字符时提交订单会因为插入失败而报错。只放宽长度，已有的数据不用改。
ALTER TABLE trade_order ALTER COLUMN merchant_name TYPE varchar(160);
