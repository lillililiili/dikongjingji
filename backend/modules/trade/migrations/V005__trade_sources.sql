-- E09：业务来源接入交易（docs/redesign/03-App实现任务计划.md §20）。第一个来源是收费活动报名（ACTIVITY）。
-- 已有的数据都是商城订单（source = 'MALL'，有配送行、有商家类型），下面每一条新约束对它们都成立，不用改数据。

-- 订单来源加 ACTIVITY。
ALTER TABLE trade_order DROP CONSTRAINT trade_order_source_check;
ALTER TABLE trade_order ADD CONSTRAINT trade_order_source_check CHECK (source IN ('MALL', 'ACTIVITY'));

-- 业务单 ID（收费报名时是报名单 ID）：商城订单为空，其余必填；同一来源的同一张业务单只能有一张交易订单。
-- 不加外键：业务表属于别的模块，迁移顺序也不保证在前。
ALTER TABLE trade_order ADD COLUMN source_ref uuid;
ALTER TABLE trade_order ADD CONSTRAINT ck_trade_order_source_ref
    CHECK ((source = 'MALL') = (source_ref IS NULL));
CREATE UNIQUE INDEX uq_trade_order_source_ref
    ON trade_order (source, source_ref)
    WHERE source_ref IS NOT NULL;

-- 配送方式加 NONE：非商城订单不发货，只能是 NONE，也不建配送行；商城订单仍是快递或自提。
ALTER TABLE trade_order DROP CONSTRAINT trade_order_delivery_mode_check;
ALTER TABLE trade_order ADD CONSTRAINT trade_order_delivery_mode_check
    CHECK (delivery_mode IN ('EXPRESS', 'PICKUP', 'NONE'));
ALTER TABLE trade_order ADD CONSTRAINT ck_trade_order_delivery_by_source
    CHECK ((source = 'MALL') = (delivery_mode <> 'NONE'));

-- 商家类型（厂家直营、授权经销、本地门店）是商城的概念：只在商城订单上必填，收费报名的主办机构没有。
ALTER TABLE trade_order ALTER COLUMN merchant_kind DROP NOT NULL;
ALTER TABLE trade_order ADD CONSTRAINT ck_trade_order_merchant_kind
    CHECK (source <> 'MALL' OR merchant_kind IS NOT NULL);

-- 退款发起方加 SOURCE：业务模块发起的退款（报名单按凭证退改，或付款到账时业务收不下的自动退款）。
ALTER TABLE trade_refund DROP CONSTRAINT trade_refund_initiator_check;
ALTER TABLE trade_refund ADD CONSTRAINT trade_refund_initiator_check
    CHECK (initiator IN ('BUYER_CANCEL', 'MERCHANT', 'SOURCE'));
-- 自动退款没有操作人（由渠道的付款结果触发），只有 SOURCE 可以不记操作人。
ALTER TABLE trade_refund ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE trade_refund ADD CONSTRAINT ck_trade_refund_requested_by
    CHECK (requested_by IS NOT NULL OR initiator = 'SOURCE');

-- 状态变化原因加 SOURCE_REFUND：业务模块发起的退款把订单带进退款中。
ALTER TABLE trade_state_transition DROP CONSTRAINT trade_state_transition_cause_check;
ALTER TABLE trade_state_transition ADD CONSTRAINT trade_state_transition_cause_check
    CHECK (cause IN ('SUBMIT', 'PAYMENT_NOTIFIED', 'PAYMENT_QUERIED', 'BUYER_CANCEL', 'TIMEOUT',
                     'SHIPMENT', 'PICKUP', 'RECEIPT', 'AUTO_RECEIPT', 'REFUND_NOTIFIED',
                     'REFUND_QUERIED', 'MERCHANT_REFUND', 'REFUND_REJECTED', 'SOURCE_REFUND'));
