'use strict';

// S6 场景 6 的只读核对：各业务模块的订单状态和交易模块的订单、支付、退款状态是否一致。只有 SELECT。
// 每条规则返回“检查了几行、违反了几行、违反的前几个 ID”。业务表通过 trade_order_id 指向交易订单（模块之间只存 ID）。

const { psql } = require('./lib.cjs');

const PAID = `('PAID','SHIPPED','COMPLETED','REFUNDING','REFUNDED','REFUND_FAILED')`;
const KEPT = `('PAID','SHIPPED','COMPLETED','REFUNDING','REFUND_FAILED')`; // 付了款、没有全额退完
const BACK = `('REFUNDING','REFUNDED','REFUND_FAILED')`; // 在退或已退

/** [规则名, 检查的行, 违反条件]：from 子句里 t 是 trade_order，b 是业务行。 */
const RULES = [
  ['活动报名 ↔ 交易订单', 'activity_enrollment b JOIN trade_order t ON t.id = b.trade_order_id',
    `NOT ((b.status = 'PENDING_PAYMENT' AND t.status = 'PENDING_PAYMENT') OR (b.status = 'CLOSED' AND t.status IN ('CLOSED','REFUNDING','REFUNDED'))
      OR (b.status = 'CONFIRMED' AND t.status IN ${KEPT}) OR (b.status = 'CANCELLED' AND t.status IN ${BACK}))`],
  ['到馆预约 ↔ 交易订单', 'venue_booking b JOIN trade_order t ON t.id = b.trade_order_id',
    `NOT ((b.status = 'PENDING_PAYMENT' AND t.status = 'PENDING_PAYMENT') OR (b.status = 'CLOSED' AND t.status IN ('CLOSED','REFUNDING','REFUNDED'))
      OR (b.status IN ('CONFIRMED','CHECKED_IN') AND t.status IN ${KEPT}) OR (b.status = 'CANCELLED' AND t.status IN ${BACK}))`],
  ['租赁订单 ↔ 交易订单', 'rental_order b JOIN trade_order t ON t.id = b.trade_order_id',
    `NOT ((b.status = 'PENDING_PAYMENT' AND t.status = 'PENDING_PAYMENT') OR (b.status = 'CLOSED' AND t.status IN ('CLOSED','REFUNDING','REFUNDED'))
      OR (b.status IN ('AWAITING_HANDOVER','HANDOVER_RECORDED','IN_USE','RETURN_RECORDED','ENDED') AND t.status IN ${KEPT})
      OR (b.status = 'CANCELLED' AND t.status IN ${BACK}))`],
  ['课程开通 ↔ 交易订单', 'course_enrollment b JOIN trade_order t ON t.id = b.trade_order_id',
    `NOT ((b.status = 'PENDING_PAYMENT' AND t.status = 'PENDING_PAYMENT') OR (b.status = 'CLOSED' AND t.status IN ('CLOSED','REFUNDING','REFUNDED'))
      OR (b.status IN ('ACTIVE','EXPIRED') AND t.status IN ${KEPT}) OR (b.status = 'REFUNDED' AND t.status IN ${BACK}))`],
  ['服务订单付款 ↔ 交易订单', 'service_order_payment b JOIN trade_order t ON t.id = b.trade_order_id',
    `NOT ((b.status = 'PENDING' AND t.status = 'PENDING_PAYMENT') OR (b.status = 'CLOSED' AND t.status IN ('CLOSED','REFUNDING','REFUNDED'))
      OR (b.status = 'PAID' AND t.status IN ${PAID}))`],
  ['巡检付款 ↔ 交易订单', 'inspection_payment_attempt b JOIN trade_order t ON t.id = b.trade_order_id',
    `NOT ((b.status = 'OPEN' AND t.status = 'PENDING_PAYMENT') OR (b.status = 'CLOSED' AND t.status IN ('CLOSED','REFUNDING','REFUNDED'))
      OR (b.status = 'PAID' AND t.status IN ${PAID}))`],
  ['业务行指向的交易订单都存在', `(SELECT trade_order_id AS ref, id, created_at FROM activity_enrollment UNION ALL SELECT trade_order_id, id, created_at FROM venue_booking
      UNION ALL SELECT trade_order_id, id, created_at FROM rental_order UNION ALL SELECT trade_order_id, id, created_at FROM course_enrollment
      UNION ALL SELECT trade_order_id, id, created_at FROM service_order_payment UNION ALL SELECT trade_order_id, id, created_at FROM inspection_payment_attempt) b`,
    'b.ref IS NOT NULL AND NOT EXISTS (SELECT 1 FROM trade_order t WHERE t.id = b.ref)'],
  ['非商城的交易订单都有业务行', `trade_order b`,
    `b.source <> 'MALL' AND NOT EXISTS (SELECT 1 FROM (SELECT trade_order_id FROM activity_enrollment UNION ALL SELECT trade_order_id FROM venue_booking
      UNION ALL SELECT trade_order_id FROM rental_order UNION ALL SELECT trade_order_id FROM course_enrollment
      UNION ALL SELECT trade_order_id FROM service_order_payment UNION ALL SELECT trade_order_id FROM inspection_payment_attempt) x WHERE x.trade_order_id = b.id)`],
  ['交易订单已付金额 = 成功支付金额', 'trade_order b',
    `b.paid_minor <> coalesce((SELECT sum(p.amount_minor) FROM trade_payment p WHERE p.order_id = b.id AND p.status = 'SUCCEEDED'), 0)`],
  ['交易订单已退金额 = 成功退款金额之和', 'trade_order b',
    `b.refunded_minor <> coalesce((SELECT sum(r.amount_minor) FROM trade_refund r WHERE r.order_id = b.id AND r.status = 'SUCCEEDED'), 0)`],
  ['已付款的状态都有成功支付，待付款、已关闭的没有', 'trade_order b',
    `(b.status IN ${PAID}) <> EXISTS (SELECT 1 FROM trade_payment p WHERE p.order_id = b.id AND p.status = 'SUCCEEDED')`],
  ['已退款的订单退满了', 'trade_order b', `b.status = 'REFUNDED' AND b.refunded_minor <> b.paid_minor`],
  ['退款金额不超过所属支付', 'trade_refund b JOIN trade_payment p ON p.id = b.payment_id', `b.amount_minor > p.amount_minor`],
  ['支付金额 = 订单应付', 'trade_payment b JOIN trade_order t ON t.id = b.order_id', `b.status = 'SUCCEEDED' AND b.amount_minor <> t.payable_minor`],
  ['收款子商户号和订单一致', 'trade_payment b JOIN trade_order t ON t.id = b.order_id', `b.sub_mchid <> t.sub_mchid`],
  ['同一笔支付、同一笔退款最多一条生效的渠道回调', 'trade_channel_notification b',
    `b.outcome = 'APPLIED' AND (SELECT count(*) FROM trade_channel_notification x WHERE x.outcome = 'APPLIED'
       AND x.out_trade_no IS NOT DISTINCT FROM b.out_trade_no AND x.out_refund_no IS NOT DISTINCT FROM b.out_refund_no AND x.event_type = b.event_type) > 1`],
];

/**
 * 跑全部规则，返回 [{ rule, checked, violations, sample }]。since（数据库时间）给了时只看这之后建的行（演练自己的数据），
 * 不给时看整个库。
 */
function crossCheck(since) {
  const sql = RULES.map(([name, from, bad]) => {
    const time = from.startsWith('trade_channel_notification') ? 'b.received_at' : 'b.created_at';
    const scope = since ? `WHERE ${time} >= '${since}'::timestamptz` : '';
    return `SELECT '${name.replace(/'/g, "''")}' || '|' || count(*) || '|' || count(*) FILTER (WHERE ${bad})
      || '|' || coalesce((array_agg(b.id::text) FILTER (WHERE ${bad}))[1:3]::text, '{}') FROM ${from} ${scope}`;
  }).join('\nUNION ALL\n');
  return psql(sql).split('\n').filter(Boolean).map((line) => {
    const [rule, checked, violations, sample] = line.split('|');
    return { rule, checked: Number(checked), violations: Number(violations), sample };
  });
}

module.exports = { crossCheck };
