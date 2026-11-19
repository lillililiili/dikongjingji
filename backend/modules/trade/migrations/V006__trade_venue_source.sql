-- E10：到馆预约接入交易（docs/redesign/03-App实现任务计划.md §21）。订单来源加 VENUE，收款方是场馆运营机构。
-- V005 已经让非商城订单必须带业务单、不发货、没有商家类型，这里只放开来源的取值；已有的数据不用改。
ALTER TABLE trade_order DROP CONSTRAINT trade_order_source_check;
ALTER TABLE trade_order ADD CONSTRAINT trade_order_source_check CHECK (source IN ('MALL', 'ACTIVITY', 'VENUE'));
