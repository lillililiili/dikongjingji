-- S4-3：通用服务订单接入交易（docs/redesign/03-App实现任务计划.md §28）。订单来源加 PRO_SERVICE，收款方是服务商。
-- 每一次付款尝试是一张交易订单，source_ref 是服务订单模块的付款尝试 ID。
-- 取值里带上 S4-1 的 INSPECTION（交易 V008，另一个分支）：两边先后合并时，后跑的这条不会把它去掉。
-- V005 已经让非商城订单必须带业务单、不发货、没有商家类型，这里只放开来源的取值；已有的数据不用改。
ALTER TABLE trade_order DROP CONSTRAINT trade_order_source_check;
ALTER TABLE trade_order ADD CONSTRAINT trade_order_source_check
    CHECK (source IN ('MALL', 'ACTIVITY', 'VENUE', 'RENTAL', 'INSPECTION', 'PRO_SERVICE'));
