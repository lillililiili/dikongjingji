#!/usr/bin/env node
'use strict';

// E10 到馆预约：参与人年龄判断、预约占名额并建交易订单、金额核对、重放、付款后出码、改期换码、
// 场馆核销、整单取消退款、未付款取消、超时关闭、关闭后才到账的恢复和自动退款。
// 真实开发库 + 本机模拟微信（infra/dev/fake-wechat/server.mjs，后端带着它写出的 backend.env 启动）。
// 环境变量见 lib.cjs；只对本机运行，跑完清掉脚本自己建的预约、参与人和 dev-customer 的订单，
// 临时挪动过的场次时间也挪回去（--keep 保留数据）。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/venue.cjs

const { BUYER, check, call, fake, psql, login, cleanupBuyer, key, waitFor, finish } = require('./lib.cjs');

// 开发种子 R__dev_experience_catalog.sql：东营低空与机器人体验馆的“机器人避障闯关”（6 岁以上，¥39/人）。
const VENUE = 'b2000000-0000-4000-8000-000000000001';
const PROJECT = 'b4000000-0000-4000-8000-000000000101';
const UNIT = 3900;
const NAMES = { a: '冒烟到馆甲', b: '冒烟到馆乙', young: '冒烟到馆丙' };

const created = new Set();
const moved = new Map();
const seats = (session) => Number(psql(`SELECT booked_count FROM venue_session WHERE id='${session}'`));
const outTradeNo = (orderId) => psql(`SELECT out_trade_no FROM trade_payment WHERE order_id='${orderId}' ORDER BY created_at DESC LIMIT 1`);
const tradeStatus = (orderId) => psql(`SELECT status FROM trade_order WHERE id='${orderId}'`);
const bookingStatus = (id) => psql(`SELECT status FROM venue_booking WHERE id='${id}'`);
const read = (token, id) => call('GET', `/api/v1/venue-bookings/${id}`, { token });
const launch = (token, orderId) => call('POST', `/api/v1/trade/orders/${orderId}/payments`, { token, headers: { 'Idempotency-Key': key() }, body: { channel: 'WECHAT_APP' } });

async function book(token, session, participantIds, amount, idempotencyKey = key()) {
  const r = await call('POST', '/api/v1/venue-bookings', { token, headers: { 'Idempotency-Key': idempotencyKey }, body: { sessionId: session, participantIds, expectedAmountMinor: amount, noticeAgreed: true } });
  if (r.json?.bookingId) created.add(r.json.bookingId);
  return r;
}

/** 接下来几天里还有至少 need 个余位、还没开始的场次，按时间排。 */
async function openSessions(need, count) {
  const found = [];
  for (let day = 1; day <= 7 && found.length < count; day += 1) {
    const date = new Date(Date.now() + day * 86_400_000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Shanghai' });
    const r = await call('GET', `/api/v1/venues/${VENUE}/sessions?cityCode=370500&projectId=${PROJECT}&date=${date}`);
    for (const s of r.json?.sessions ?? []) {
      if (s.status === 'OPEN' && s.seatsLeft >= need && found.length < count) found.push(s.sessionId);
    }
  }
  return found;
}

async function children(token) {
  const births = { a: '2016-05', b: '2016-05', young: '2022-01' };
  for (const [k, name] of Object.entries(NAMES)) {
    await call('POST', '/api/v1/activity-participants', { token, body: { name, birthMonth: births[k], guardianConfirmed: true } });
  }
  const list = (await call('GET', '/api/v1/activity-participants', { token })).json.items;
  return Object.fromEntries(Object.entries(NAMES).map(([k, name]) => [k, list.find((p) => p.name === name)?.participantId]));
}

/** 模拟“本地已经超时关闭、微信那边其实付了”：按关闭时的样子改预约、名额和交易订单。 */
function closeLocally(bookingId, orderId, session, people) {
  psql(`
    BEGIN;
    UPDATE trade_order SET status = 'CLOSED', closed_at = now(), version = version + 1 WHERE id = '${orderId}';
    UPDATE trade_payment SET status = 'CLOSED', updated_at = now() WHERE order_id = '${orderId}' AND status IN ('CREATED', 'PREPAID');
    INSERT INTO trade_state_transition (order_id, from_status, to_status, cause, occurred_at)
      VALUES ('${orderId}', 'PENDING_PAYMENT', 'CLOSED', 'TIMEOUT', now());
    UPDATE venue_booking SET status = 'CLOSED', closed_at = now(), version = version + 1 WHERE id = '${bookingId}';
    UPDATE venue_booking_participant SET active = false WHERE booking_id = '${bookingId}';
    UPDATE venue_session SET booked_count = booked_count - ${people} WHERE id = '${session}';
    COMMIT;
  `);
}

/** 把一个场次临时挪到“马上开始”，核销窗口才开；结束时挪回去。 */
function moveToNow(session) {
  const original = psql(`SELECT starts_at || '|' || ends_at FROM venue_session WHERE id='${session}'`);
  moved.set(session, original);
  psql(`UPDATE venue_session SET starts_at = now() + interval '10 minutes', ends_at = now() + interval '40 minutes' WHERE id='${session}'`);
}

function cleanup() {
  const ids = [...created].map((id) => `'${id}'`).join(',');
  if (ids) {
    psql(`
      BEGIN;
      UPDATE venue_session s SET booked_count = s.booked_count - t.n
        FROM (SELECT b.session_id, count(*) AS n FROM venue_booking_participant p JOIN venue_booking b ON b.id = p.booking_id
              WHERE p.booking_id IN (${ids}) AND p.active GROUP BY b.session_id) t
       WHERE s.id = t.session_id;
      DELETE FROM venue_booking_event WHERE booking_id IN (${ids});
      DELETE FROM venue_booking_participant WHERE booking_id IN (${ids});
      DELETE FROM venue_booking WHERE id IN (${ids});
      COMMIT;
    `);
  }
  for (const [session, original] of moved) {
    const [starts, ends] = original.split('|');
    psql(`UPDATE venue_session SET starts_at = '${starts}', ends_at = '${ends}' WHERE id='${session}'`);
  }
  const names = Object.values(NAMES).map((n) => `'${n}'`).join(',');
  psql(`DELETE FROM activity_participant p WHERE owner_id = '${BUYER}' AND name IN (${names})
        AND NOT EXISTS (SELECT 1 FROM activity_enrollment_ticket t WHERE t.participant_id = p.id)`);
  cleanupBuyer();
}

async function main() {
  const customer = await login('dev-customer');
  const organizer = await login('dev-organizer');
  const kids = await children(customer);
  check('three smoke children exist', kids.a && kids.b && kids.young, kids);
  const [s1, s2, s3, s4] = await openSessions(2, 4);
  check('found four open sessions with two seats', s1 && s2 && s3 && s4, [s1, s2, s3, s4]);

  // 0. 场馆详情告诉页面能在线付款；参与人按年龄判断
  let r = await call('GET', `/api/v1/venues/${VENUE}?cityCode=370500`);
  check('project says online payment is available', r.json?.projects?.find((p) => p.projectId === PROJECT)?.onlinePayment === true);
  r = await call('GET', `/api/v1/venue-sessions/${s1}/participants`, { token: customer });
  const item = (id) => r.json?.items?.find((p) => p.participantId === id);
  check('children of 10 are eligible, a child of 4 is not', item(kids.a)?.eligible === true && item(kids.young)?.eligible === false && item(kids.young)?.reason === 'UNDER_MIN_AGE', r.json);
  check('participants need a login', (await call('GET', `/api/v1/venue-sessions/${s1}/participants`)).status === 401);

  // 1. 预约：占名额、建交易订单
  const before1 = seats(s1);
  r = await book(customer, s1, [kids.a, kids.b], UNIT);
  check('wrong amount is refused', r.status === 409 && r.json.code === 'VENUE_AMOUNT_CHANGED', r.json);
  r = await book(customer, s1, [kids.young], UNIT);
  check('a child under the minimum age is refused', r.status === 422 && r.json.code === 'VENUE_PARTICIPANT_NOT_ELIGIBLE', r.json);
  check('refusals took no seats', seats(s1) === before1);
  const k1 = key();
  r = await book(customer, s1, [kids.a, kids.b], UNIT * 2, k1);
  const b1 = r.json;
  check('booking created as pending payment without a code', r.status === 201 && b1.status === 'PENDING_PAYMENT' && b1.code === null && b1.payment.amountMinor === UNIT * 2 && b1.payment.tradeOrderId && b1.people === 2, b1);
  check('two seats held', seats(s1) === before1 + 2);
  r = await book(customer, s1, [kids.a, kids.b], UNIT * 2, k1);
  check('replay returns the same booking', [200, 201].includes(r.status) && r.json.bookingId === b1.bookingId && seats(s1) === before1 + 2, r.json);
  r = await book(customer, s1, [kids.a], UNIT);
  check('the same child cannot book the session twice', r.status === 409 && r.json.code === 'VENUE_PARTICIPANT_ALREADY_BOOKED', r.json);
  const order1 = b1.payment.tradeOrderId;
  r = await call('GET', `/api/v1/trade/orders/${order1}`, { token: customer });
  check('trade order is a venue order without delivery', r.status === 200 && r.json.source === 'VENUE' && r.json.sourceRef === b1.bookingId && !r.json.delivery, r.json);
  r = await call('GET', '/api/v1/trade/orders?tab=ALL&page=1&size=50', { token: customer });
  check('goods-order list leaves venue orders out', !(r.json.items ?? []).some((o) => o.orderId === order1));

  // 2. 付款：出码
  await launch(customer, order1);
  const fakeOrder = (await fake('GET', '/_control/orders')).json.find((o) => o.out_trade_no === outTradeNo(order1));
  check('wechat order goes to the venue operator sub merchant', fakeOrder?.sub_mchid === '1900000204' && fakeOrder.total === UNIT * 2, fakeOrder);
  await fake('POST', `/_control/pay/${outTradeNo(order1)}?notify=1`);
  r = await read(customer, b1.bookingId);
  const code1 = r.json.code;
  check('paid booking is confirmed with one code', r.json.status === 'CONFIRMED' && r.json.payment.status === 'PAID' && /^VEN-/.test(code1) && r.json.reschedulable && r.json.cancellable, r.json);
  r = await call('POST', '/api/v1/venue-checkins', { token: organizer, body: { code: code1 } });
  check('check-in before the window is refused', r.status === 409 && r.json.code === 'VENUE_CHECKIN_NOT_OPEN', r.json);
  r = await call('POST', '/api/v1/venue-checkins', { token: customer, body: { code: code1 } });
  check('a customer cannot check in', r.status === 403, r.json);

  // 3. 改期：换码、名额跟着走
  const before2 = seats(s2);
  r = await call('POST', `/api/v1/venue-bookings/${b1.bookingId}/reschedule`, { token: customer, headers: { 'If-Match': '"0"' }, body: { sessionId: s2 } });
  check('a stale version is refused', r.status === 412, r.json);
  const etag = (await read(customer, b1.bookingId)).etag;
  r = await call('POST', `/api/v1/venue-bookings/${b1.bookingId}/reschedule`, { token: customer, headers: { 'If-Match': etag }, body: { sessionId: s2 } });
  check('reschedule moves the booking and changes the code', r.status === 200 && r.json.session.sessionId === s2 && r.json.code && r.json.code !== code1, r.json);
  check('seats moved with it', seats(s1) === before1 && seats(s2) === before2 + 2, [seats(s1), before1, seats(s2), before2]);
  r = await call('POST', '/api/v1/venue-checkins', { token: organizer, body: { code: code1 } });
  check('the old code is gone', r.status === 404, r.json);

  // 4. 取消已付款的：整单原路退
  r = await call('POST', `/api/v1/venue-bookings/${b1.bookingId}/cancel`, { token: customer });
  check('cancelled with a full refund', r.status === 200 && r.json.status === 'CANCELLED' && r.json.code === null && r.json.refund?.amountMinor === UNIT * 2, r.json);
  check('seats released', seats(s2) === before2);
  check('refund initiated by the source for the whole amount', psql(`SELECT initiator || ':' || amount_minor FROM trade_refund WHERE order_id='${order1}'`) === `SOURCE:${UNIT * 2}`);
  const outRefundNo = psql(`SELECT out_refund_no FROM trade_refund WHERE order_id='${order1}'`);
  await waitFor(() => psql(`SELECT status FROM trade_refund WHERE order_id='${order1}'`) === 'PROCESSING', 30);
  await fake('POST', `/_control/refund/${outRefundNo}?status=SUCCESS&notify=1`);
  r = await read(customer, b1.bookingId);
  check('refund shows as succeeded and the order is refunded', r.json.refund?.status === 'SUCCEEDED' && tradeStatus(order1) === 'REFUNDED', [r.json.refund, tradeStatus(order1)]);

  // 5. 核销：场次挪到马上开始
  r = await book(customer, s3, [kids.a], UNIT);
  const b3 = r.json;
  await launch(customer, b3.payment.tradeOrderId);
  await fake('POST', `/_control/pay/${outTradeNo(b3.payment.tradeOrderId)}?notify=1`);
  const code3 = (await read(customer, b3.bookingId)).json.code;
  moveToNow(s3);
  r = await call('POST', '/api/v1/venue-checkins', { token: organizer, body: { code: code3.toLowerCase() } });
  check('the venue operator checks the booking in', r.status === 200 && r.json.repeated === false && r.json.people === 1, r.json);
  r = await call('POST', '/api/v1/venue-checkins', { token: organizer, body: { code: code3 } });
  check('checking in again repeats the first result', r.status === 200 && r.json.repeated === true, r.json);
  r = await call('POST', `/api/v1/venue-bookings/${b3.bookingId}/cancel`, { token: customer });
  check('a checked-in booking cannot be cancelled', r.status === 409 && r.json.code === 'VENUE_CANCEL_NOT_ALLOWED', r.json);
  check('booking reads as checked in', (await read(customer, b3.bookingId)).json.status === 'CHECKED_IN');

  // 6. 未付款取消：关闭并释放名额
  const before4 = seats(s4);
  r = await book(customer, s4, [kids.b], UNIT);
  const b4 = r.json;
  await launch(customer, b4.payment.tradeOrderId);
  r = await call('POST', `/api/v1/venue-bookings/${b4.bookingId}/cancel`, { token: customer });
  check('cancelling an unpaid booking closes it', r.status === 200 && r.json.status === 'CLOSED' && tradeStatus(b4.payment.tradeOrderId) === 'CLOSED' && seats(s4) === before4, r.json);

  // 7. 超时：定时任务关单
  r = await book(customer, s4, [kids.b], UNIT);
  const b5 = r.json;
  psql(`UPDATE trade_order SET pay_before = now() - interval '1 second' WHERE id='${b5.payment.tradeOrderId}'`);
  const timedOut = await waitFor(() => bookingStatus(b5.bookingId) === 'CLOSED', 150);
  check('timeout job closes the booking and releases the seat', timedOut && seats(s4) === before4, [bookingStatus(b5.bookingId), seats(s4), before4]);

  // 8. 关闭之后才到账：名额还在就恢复
  r = await book(customer, s4, [kids.b], UNIT);
  const b6 = r.json;
  await launch(customer, b6.payment.tradeOrderId);
  closeLocally(b6.bookingId, b6.payment.tradeOrderId, s4, 1);
  await fake('POST', `/_control/pay/${outTradeNo(b6.payment.tradeOrderId)}?notify=1`);
  r = await read(customer, b6.bookingId);
  check('late payment restores the booking with a code', r.json.status === 'CONFIRMED' && /^VEN-/.test(r.json.code) && seats(s4) === before4 + 1, r.json);

  // 9. 关闭之后才到账、人已经另约了：整笔自动退款
  r = await book(customer, s1, [kids.b], UNIT);
  const b7 = r.json;
  await launch(customer, b7.payment.tradeOrderId);
  closeLocally(b7.bookingId, b7.payment.tradeOrderId, s1, 1);
  r = await book(customer, s1, [kids.b], UNIT);
  check('the child books that session again', r.status === 201, r.json);
  await fake('POST', `/_control/pay/${outTradeNo(b7.payment.tradeOrderId)}?notify=1`);
  check('late payment that cannot be restored leaves the booking closed', bookingStatus(b7.bookingId) === 'CLOSED');
  check('and refunds the whole amount automatically', psql(`SELECT initiator || ':' || amount_minor FROM trade_refund WHERE order_id='${b7.payment.tradeOrderId}'`) === `SOURCE:${UNIT}` && tradeStatus(b7.payment.tradeOrderId) === 'REFUNDING');
}

const done = finish(cleanup);
main().then(() => done(), done);
