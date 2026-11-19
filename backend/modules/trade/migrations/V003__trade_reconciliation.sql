-- E06：对账。

-- 微信受理退款的时间。微信的交易账单按“发起退款成功”（受理）的日期出退款行，不是按到账日期，
-- 对账时平台这边也要按这个时间分天。已经受理过的历史退款用最后一次更新的时间补上（E05 之前没有退款数据，只是保险）。
ALTER TABLE trade_refund ADD COLUMN accepted_at timestamptz;
UPDATE trade_refund SET accepted_at = updated_at WHERE refund_id IS NOT NULL AND accepted_at IS NULL;
CREATE INDEX idx_trade_refund_accepted ON trade_refund (accepted_at) WHERE accepted_at IS NOT NULL;

-- 按付款成功时间取某一天的支付单。
CREATE INDEX idx_trade_payment_succeeded ON trade_payment (succeeded_at) WHERE status = 'SUCCEEDED';
