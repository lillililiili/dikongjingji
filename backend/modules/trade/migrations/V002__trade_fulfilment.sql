-- E05：发货、自提核销、确认收货、退款。

-- 自提取货码输错的次数。取货码只有 6 位数字，连续输错 10 次后这张单不能再核销，买家找客服处理，
-- 免得有人逐个试号码把别人的货“核销”掉。核销成功后不再用到。
ALTER TABLE trade_order_delivery
    ADD COLUMN pickup_failures smallint NOT NULL DEFAULT 0 CHECK (pickup_failures BETWEEN 0 AND 10);

-- 退款被渠道当场拒绝（比如商家账户余额不足）也要留痕：REFUND_REJECTED。
ALTER TABLE trade_state_transition DROP CONSTRAINT trade_state_transition_cause_check;
ALTER TABLE trade_state_transition ADD CONSTRAINT trade_state_transition_cause_check
    CHECK (cause IN ('SUBMIT', 'PAYMENT_NOTIFIED', 'PAYMENT_QUERIED', 'BUYER_CANCEL', 'TIMEOUT',
                     'SHIPMENT', 'PICKUP', 'RECEIPT', 'AUTO_RECEIPT', 'REFUND_NOTIFIED',
                     'REFUND_QUERIED', 'MERCHANT_REFUND', 'REFUND_REJECTED'));

-- 退款的定时兜底：还没提交成功的（REQUESTED）重新提交，处理中和异常的去查询。
CREATE INDEX idx_trade_refund_open ON trade_refund (status, updated_at)
    WHERE status IN ('REQUESTED', 'PROCESSING', 'ABNORMAL');
