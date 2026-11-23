-- S4-1：巡检付款节点接入交易（docs/redesign/08-后续开发计划.md §5 D9、C3）。订单来源加 INSPECTION，收款方是巡检服务商。
-- 每个付款节点的每一次付款尝试是一张交易订单，source_ref 是巡检模块的付款尝试 ID。
-- V005 已经让非商城订单必须带业务单、不发货、没有商家类型，这里只放开来源的取值；已有的数据不用改。
ALTER TABLE trade_order DROP CONSTRAINT trade_order_source_check;
ALTER TABLE trade_order ADD CONSTRAINT trade_order_source_check
    CHECK (source IN ('MALL', 'ACTIVITY', 'VENUE', 'RENTAL', 'INSPECTION'));
