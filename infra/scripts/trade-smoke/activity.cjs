#!/usr/bin/env node
'use strict';

// E09 收费活动报名：报名占名额并建交易订单、金额核对、重放、付款后凭证生效、退一位按单价退款、
// 未付款取消释放名额、超时关闭释放名额、关闭后才到账的恢复和自动退款。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）。
// 环境变量见 lib.cjs；只对本机运行，跑完清掉脚本自己建的报名、参与人和 dev-customer 的订单（--keep 保留）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/activity.cjs

const { BUYER, check, call, fake, psql, login, cleanupBuyer, key, waitFor, finish } = require('./lib.cjs');

// 开发种子 R__dev_activity_catalog.sql：机器人研学体验（6–12 岁，¥168/人），主办机构已登记子商户号。
const ACTIVITY = 'a2000000-0000-4000-8000-000000000002';
const SESSION_A = 'a3000000-0000-4000-8000-000000000201';
const SESSION_B = 'a3000000-0000-4000-8000-000000000202';
const SESSION_C = 'a3000000-0000-4000-8000-000000000203';
const UNIT = 16800;
const CHILD_NAMES = ['冒烟甲', '冒烟乙'];

const created = new Set();
const seats = (session) => Number(psql(`SELECT enrolled_count FROM activity_session WHERE id='${session}'`));
const outTradeNo = (orderId) => psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`);
const tradeStatus = (orderId) => psql(`SELECT status FROM trade_order WHERE id='${orderId}'`);
const enrollmentStatus = (id) => psql(`SELECT status FROM activity_enrollment WHERE id='${id}'`);
const read = (token, id) => call('GET', `/api/v1/activity-enrollments/${id}`, { token });
const launch = (token, orderId) => call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });

async function enroll(token, session, participantIds, amount, idempotencyKey = key()) {
  const body = { activityId: ACTIVITY, sessionId: session, participantIds, noticeAgreed: true };
  if (amount !== undefined) body.expectedAmountMinor = amount;
  const r = await call('POST', '/api/v1/activity-enrollments', { token, headers: { 'Idempotency-Key': idempotencyKey }, body });
  if (r.json?.enrollmentId) created.add(r.json.enrollmentId);
  return r;
}

async function children(token) {
  for (const name of CHILD_NAMES) {
    await call('POST', '/api/v1/activity-participants', { token, body: { name, birthMonth: '2016-05', guardianConfirmed: true } });
  }
  const r = await call('GET', '/api/v1/activity-participants', { token });
  const list = r.json?.items ?? r.json?.participants ?? r.json;
  return CHILD_NAMES.map((name) => list.find((p) => p.name === name)?.participantId);
}

/** 模拟“本地已经超时关闭、微信那边其实付了”：按关闭时的样子改报名单、凭证、名额和交易订单。 */
function closeLocally(enrollmentId, orderId, session, people) {
  psql(`
    BEGIN;
    UPDATE trade_order SET status = 'CLOSED', closed_at = now(), version = version + 1 WHERE id = '${orderId}';
    UPDATE trade_payment SET status = 'CLOSED', updated_at = now() WHERE order_id = '${orderId}' AND status IN ('CREATED', 'PREPAID');
    INSERT INTO trade_state_transition (order_id, from_status, to_status, cause, occurred_at)
      VALUES ('${orderId}', 'PENDING_PAYMENT', 'CLOSED', 'TIMEOUT', now());
    UPDATE activity_enrollment SET status = 'CLOSED', closed_at = now(), version = version + 1 WHERE id = '${enrollmentId}';
    UPDATE activity_enrollment_ticket SET status = 'CANCELLED', cancelled_at = now(), cancel_cause = 'PAYMENT_CLOSED'
      WHERE enrollment_id = '${enrollmentId}';
    UPDATE activity_session SET enrolled_count = enrolled_count - ${people} WHERE id = '${session}';
    COMMIT;
  `);
}

function cleanup() {
  const names = CHILD_NAMES.map((n) => `'${n}'`).join(',');
  // 这次建的报名，加上以前（比如上次清理失败）用同样两个参与人建的，和 aftersale.cjs 一样。
  psql(`SELECT DISTINCT t.enrollment_id FROM activity_enrollment_ticket t JOIN activity_participant p ON p.id = t.participant_id
        WHERE p.owner_id = '${BUYER}' AND p.name IN (${names})`).split('\n').filter(Boolean).forEach((id) => created.add(id));
  const ids = [...created].map((id) => `'${id}'`).join(',');
  if (ids) {
    // 先把还占着名额的凭证还回去，再删。
    psql(`
      BEGIN;
      UPDATE activity_session s SET enrolled_count = s.enrolled_count - t.n
        FROM (SELECT session_id, count(*) AS n FROM activity_enrollment_ticket
              WHERE enrollment_id IN (${ids}) AND status <> 'CANCELLED' GROUP BY session_id) t
       WHERE s.id = t.session_id;
      DELETE FROM activity_enrollment_event WHERE enrollment_id IN (${ids});
      DELETE FROM activity_enrollment_ticket WHERE enrollment_id IN (${ids});
      -- 活动 V006（S5-3）起每次取消记一条退改记录，指向报名单；凭证指向它，所以在凭证之后、报名单之前删。
      DELETE FROM activity_enrollment_change WHERE enrollment_id IN (${ids});
      DELETE FROM activity_enrollment WHERE id IN (${ids});
      COMMIT;
    `);
  }
  psql(`DELETE FROM activity_participant p WHERE owner_id = '${BUYER}' AND name IN (${names})
        AND NOT EXISTS (SELECT 1 FROM activity_enrollment_ticket t WHERE t.participant_id = p.id)`);
  cleanupBuyer();
}

async function main() {
  const customer = await login('dev-customer');
  const [kidA, kidB] = await children(customer);
  check('two smoke children exist', kidA && kidB, [kidA, kidB]);

  // 0. 活动详情告诉页面能在线付款
  let r = await call('GET', `/api/v1/activities/${ACTIVITY}?cityCode=370500`, { token: customer });
  check('activity says online payment is available', r.status === 200 && r.json?.price?.onlinePayment === true, r.json?.price);

  // 1. 报名：占名额、建交易订单
  const before = seats(SESSION_C);
  r = await enroll(customer, SESSION_C, [kidA, kidB], UNIT);
  check('wrong amount is refused', r.status === 409 && r.json.code === 'ACTIVITY_AMOUNT_CHANGED', r.json);
  r = await enroll(customer, SESSION_C, [kidA, kidB]);
  check('missing amount is refused', r.status >= 400 && r.status < 500, r.json);
  check('refusals took no seats', seats(SESSION_C) === before);
  const k1 = key();
  r = await enroll(customer, SESSION_C, [kidA, kidB], UNIT * 2, k1);
  const e1 = r.json;
  check('paid enrolment created as pending payment', r.status === 201 && e1.payment?.status === 'PENDING_PAYMENT' && e1.payment.amountMinor === UNIT * 2 && e1.payment.unitAmountMinor === UNIT && e1.payment.tradeOrderId, r.json);
  check('pending tickets have no code', e1?.tickets?.length === 2 && e1.tickets.every((t) => t.status === 'PENDING_PAYMENT' && t.ticketCode === null), e1?.tickets);
  check('seats are held', seats(SESSION_C) === before + 2);
  const payBefore = new Date(e1.payment.payBefore).getTime();
  check('pay window is about 15 minutes', payBefore - Date.now() > 13 * 60_000 && payBefore - Date.now() <= 15 * 60_000 + 5_000, e1.payment.payBefore);
  r = await enroll(customer, SESSION_C, [kidA, kidB], UNIT * 2, k1);
  check('replay returns the same enrolment', [200, 201].includes(r.status) && r.json.enrollmentId === e1.enrollmentId && seats(SESSION_C) === before + 2, r.json);
  check('one trade order for it', psql(`SELECT count(*) FROM trade_order WHERE source='ACTIVITY' AND source_ref='${e1.enrollmentId}'`) === '1');
  r = await enroll(customer, SESSION_C, [kidA], UNIT);
  check('the same child cannot enrol twice while unpaid', r.status === 409, r.json);
  const order1 = e1.payment.tradeOrderId;
  r = await call('GET', `/api/v1/trade/orders/${order1}`, { token: customer });
  check('trade order is an activity order without delivery', r.status === 200 && r.json.source === 'ACTIVITY' && r.json.sourceRef === e1.enrollmentId && !r.json.delivery && r.json.amounts.payableMinor === UNIT * 2, r.json);
  r = await call('GET', '/api/v1/trade/orders?tab=ALL&page=1&size=50', { token: customer });
  check('goods-order list leaves activity orders out', r.status === 200 && !(r.json.items ?? []).some((o) => o.orderId === order1), r.json?.items?.map?.((o) => o.orderId));

  // 2. 付款：凭证生效
  r = await launch(customer, order1);
  check('payment launches', r.status === 200 && r.json.appCall?.prepayId, r.json);
  const fakeOrder = (await fake('GET', '/_control/orders')).json.find((o) => o.out_trade_no === outTradeNo(order1));
  check('wechat order goes to the organiser sub merchant', fakeOrder && fakeOrder.sub_mchid === '1900000202' && fakeOrder.total === UNIT * 2, fakeOrder);
  await fake('POST', `/_control/pay/${outTradeNo(order1)}?notify=1`);
  r = await read(customer, e1.enrollmentId);
  check('paid enrolment is confirmed with codes', r.json.payment.status === 'PAID' && r.json.tickets.every((t) => t.status === 'ACTIVE' && /^ACT-/.test(t.ticketCode)), r.json);
  r = await call('POST', `/api/v1/trade/orders/${order1}/cancel`, { token: customer, headers: { 'Idempotency-Key': key(), 'If-Match': '"1"' } });
  check('trade cancel of a paid activity order is refused', r.status === 409, r.json);

  // 3. 退一位：按单价原路退
  const ticketB = r && (await read(customer, e1.enrollmentId)).json.tickets.find((t) => t.participantId === kidB);
  r = await call('POST', `/api/v1/activity-enrollments/${e1.enrollmentId}/cancel`, { token: customer, body: { ticketIds: [ticketB.ticketId] } });
  const cancelled = r.json?.tickets?.find((t) => t.ticketId === ticketB.ticketId);
  check('one ticket cancelled with a refund of one unit', r.status === 200 && cancelled?.status === 'CANCELLED' && cancelled.refund?.amountMinor === UNIT && ['REQUESTED', 'PROCESSING'].includes(cancelled.refund.status), cancelled);
  check('the other ticket is still valid', r.json?.tickets?.find((t) => t.participantId === kidA)?.status === 'ACTIVE');
  check('one seat released', seats(SESSION_C) === before + 1);
  const outRefundNo = psql(`SELECT out_refund_no FROM trade_refund WHERE order_id='${order1}'`);
  check('refund initiated by the source for one unit', psql(`SELECT initiator || ':' || amount_minor FROM trade_refund WHERE order_id='${order1}'`) === `SOURCE:${UNIT}`);
  await waitFor(() => psql(`SELECT status FROM trade_refund WHERE order_id='${order1}'`) === 'PROCESSING', 30);
  await fake('POST', `/_control/refund/${outRefundNo}?status=SUCCESS&notify=1`);
  r = await read(customer, e1.enrollmentId);
  check('refund shows as succeeded on the ticket', r.json.tickets.find((t) => t.ticketId === ticketB.ticketId)?.refund?.status === 'SUCCEEDED', r.json.tickets);
  check('trade order stays paid with one unit refunded', psql(`SELECT status || ':' || refunded_minor FROM trade_order WHERE id='${order1}'`) === `PAID:${UNIT}`);

  // 4. 未付款取消：关闭并释放名额
  const beforeA = seats(SESSION_A);
  r = await enroll(customer, SESSION_A, [kidB], UNIT);
  const e2 = r.json;
  check('second enrolment pending', r.status === 201 && seats(SESSION_A) === beforeA + 1, r.json);
  await launch(customer, e2.payment.tradeOrderId);
  r = await call('POST', `/api/v1/activity-enrollments/${e2.enrollmentId}/cancel`, { token: customer, body: {} });
  check('cancelling an unpaid enrolment closes it', r.status === 200 && r.json.payment.status === 'CLOSED' && enrollmentStatus(e2.enrollmentId) === 'CLOSED', r.json);
  check('its trade order is closed and the seat released', tradeStatus(e2.payment.tradeOrderId) === 'CLOSED' && seats(SESSION_A) === beforeA);

  // 5. 超时：定时任务关单，名额释放
  const beforeB = seats(SESSION_B);
  r = await enroll(customer, SESSION_B, [kidB], UNIT);
  const e3 = r.json;
  psql(`UPDATE trade_order SET pay_before = now() - interval '1 second' WHERE id='${e3.payment.tradeOrderId}'`);
  const timedOut = await waitFor(() => enrollmentStatus(e3.enrollmentId) === 'CLOSED', 150);
  check('timeout job closes the enrolment and releases the seat', timedOut && tradeStatus(e3.payment.tradeOrderId) === 'CLOSED' && seats(SESSION_B) === beforeB, [enrollmentStatus(e3.enrollmentId), seats(SESSION_B), beforeB]);

  // 6. 关闭之后才到账：名额还在就恢复
  r = await enroll(customer, SESSION_B, [kidB], UNIT);
  const e4 = r.json;
  await launch(customer, e4.payment.tradeOrderId);
  closeLocally(e4.enrollmentId, e4.payment.tradeOrderId, SESSION_B, 1);
  await fake('POST', `/_control/pay/${outTradeNo(e4.payment.tradeOrderId)}?notify=1`);
  r = await read(customer, e4.enrollmentId);
  check('late payment restores the enrolment', r.json.payment.status === 'PAID' && r.json.tickets[0].status === 'ACTIVE' && seats(SESSION_B) === beforeB + 1, r.json);
  check('restored in the event log', psql(`SELECT count(*) FROM activity_enrollment_event WHERE enrollment_id='${e4.enrollmentId}' AND event_type='PAYMENT_RESTORED'`) === '1');

  // 7. 关闭之后才到账、人已经另报了：整笔自动退款
  r = await enroll(customer, SESSION_A, [kidB], UNIT);
  const e5 = r.json;
  await launch(customer, e5.payment.tradeOrderId);
  closeLocally(e5.enrollmentId, e5.payment.tradeOrderId, SESSION_A, 1);
  r = await enroll(customer, SESSION_A, [kidB], UNIT);
  check('the child enrols again in that session', r.status === 201, r.json);
  await fake('POST', `/_control/pay/${outTradeNo(e5.payment.tradeOrderId)}?notify=1`);
  check('late payment that cannot be restored leaves the enrolment closed', enrollmentStatus(e5.enrollmentId) === 'CLOSED');
  check('and refunds the whole amount automatically', psql(`SELECT initiator || ':' || amount_minor FROM trade_refund WHERE order_id='${e5.payment.tradeOrderId}'`) === `SOURCE:${UNIT}` && tradeStatus(e5.payment.tradeOrderId) === 'REFUNDING');
}

const done = finish(cleanup);
main().then(() => done(), done);
