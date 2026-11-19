#!/usr/bin/env node
'use strict';

// G1 客服会话和反馈：打开会话（同一方只有一个、对方要是在用的服务商类机构）、发文字和卡片（幂等）、
// 商家 / 服务商 / 平台客服各自只看得到自己的会话、回复后客户未读和“客服消息”站内信（同一会话最多一条未读）、
// 客户读会话清零并把站内信标为已读、往前翻页、反馈（幂等）、角色。真实开发库，不用模拟微信。
// 环境变量见 lib.cjs（BASE_URL、PG_CONTAINER、PG_DB、PG_USER）；只对本机运行。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> node infra/scripts/trade-smoke/support.cjs [--keep]
// dev-customer 和这几方已经有会话时脚本不跑（客户和同一方只有一个会话，脚本没法只清掉自己加的消息）：
// 在一次性库上跑，或先删掉那几个会话。跑完删掉脚本自己建的会话、消息、站内信和反馈（--keep 保留）。

const { check, call, psql, login, key, finish, BUYER } = require('./lib.cjs');

const ROBOT = 'c1000000-0000-4000-8000-000000000001';
const YUNYI = '22222222-2222-2222-2222-222222222222';
const CUSTOMER_ORG = '11111111-1111-1111-1111-aaaaaaaaaaaa';
const ORDER = 'e3000000-0000-4000-8000-0000000000f1';
const P = '/api/v1/provider/conversations';
const S = '/api/v1/platform/support/conversations';

const conversations = new Set();
const feedback = new Set();

const open = (token, body) => call('POST', '/api/v1/conversations', { token, body });
const send = (token, route, body, idempotencyKey = key()) => call('POST', route, { token, headers: { 'Idempotency-Key': idempotencyKey }, body });
const supportNotices = async (token, conversationId) => {
  const r = await call('GET', '/api/v1/notifications?page=1&size=50', { token });
  return { unreadTotal: r.json.unreadCount, items: r.json.items.filter((n) => n.category === 'CUSTOMER_SERVICE' && n.target.id === conversationId) };
};

function cleanup() {
  if (conversations.size > 0) {
    const ids = [...conversations].map((id) => `'${id}'`).join(',');
    psql(`
      BEGIN;
      DELETE FROM notification_message WHERE category = 'CUSTOMER_SERVICE' AND target_type = 'CONVERSATION' AND target_id IN (${ids});
      DELETE FROM support_message WHERE conversation_id IN (${ids});
      DELETE FROM support_conversation WHERE id IN (${ids});
      COMMIT;
    `);
  }
  if (feedback.size > 0) {
    psql(`DELETE FROM support_feedback WHERE id IN (${[...feedback].map((id) => `'${id}'`).join(',')});`);
  }
}

async function main() {
  const existing = psql(`SELECT count(*) FROM support_conversation WHERE customer_id = '${BUYER}'
    AND (counterpart_kind = 'PLATFORM' OR counterpart_org_id IN ('${ROBOT}', '${YUNYI}'))`);
  if (existing !== '0') throw new Error(`dev-customer 已经有 ${existing} 个会话和这几方：在一次性库上跑，或先删掉它们`);

  const customer = await login('dev-customer');
  const merchant = await login('dev-merchant');
  const provider = await login('dev-provider');
  const support = await login('dev-support');

  // ---- 打开会话 ----
  const robot = await open(customer, { counterpartKind: 'MERCHANT', organizationId: ROBOT });
  check('opening a merchant conversation answers 201', robot.status === 201, robot);
  const robotId = robot.json.conversationId;
  conversations.add(robotId);
  check('the counterpart is the merchant by name only', robot.json.counterpart.name === '示例机器人商家'
    && Object.keys(robot.json.counterpart).sort().join() === 'kind,name,organizationId', robot.json);
  const again = await open(customer, { counterpartKind: 'MERCHANT', organizationId: ROBOT });
  check('opening it again answers 200 with the same conversation', again.status === 200 && again.json.conversationId === robotId, again);
  const yunyi = await open(customer, { counterpartKind: 'PROVIDER', organizationId: YUNYI });
  conversations.add(yunyi.json.conversationId);
  const platform = await open(customer, { counterpartKind: 'PLATFORM' });
  conversations.add(platform.json.conversationId);
  check('provider and platform conversations open', yunyi.status === 201 && platform.status === 201
    && platform.json.counterpart.name === '平台客服' && platform.json.counterpart.organizationId === null, [yunyi.status, platform.json]);
  for (const [label, body] of [['a customer organization', { counterpartKind: 'MERCHANT', organizationId: CUSTOMER_ORG }],
    ['an unknown organization', { counterpartKind: 'PROVIDER', organizationId: key() }]]) {
    const r = await open(customer, body);
    check(`${label} cannot be a counterpart`, r.status === 409 && r.json.code === 'SUPPORT_COUNTERPART_UNAVAILABLE', r);
  }
  const withOrg = await open(customer, { counterpartKind: 'PLATFORM', organizationId: ROBOT });
  check('the platform support takes no organization', withOrg.status === 400, withOrg);
  const empty = await call('GET', '/api/v1/conversations', { token: customer });
  check('conversations without messages are not listed', empty.status === 200
    && !empty.json.items.some((c) => conversations.has(c.conversationId)), empty.json);

  // ---- 发消息 ----
  const cardKey = key();
  const card = await send(customer, `/api/v1/conversations/${robotId}/messages`,
    { card: { type: 'MALL_ORDER', refId: ORDER, title: '编程机器人探索套装', subtitle: '已发货 · 实付 ¥1,299' } }, cardKey);
  check('a card is sent', card.status === 201 && card.json.seq === 1 && card.json.kind === 'CARD' && card.json.card.refId === ORDER, card);
  const replay = await send(customer, `/api/v1/conversations/${robotId}/messages`,
    { card: { type: 'MALL_ORDER', refId: ORDER, title: '编程机器人探索套装', subtitle: '已发货 · 实付 ¥1,299' } }, cardKey);
  check('the same key replays the same message with 200', replay.status === 200 && replay.json.messageId === card.json.messageId, replay);
  const reused = await send(customer, `/api/v1/conversations/${robotId}/messages`, { text: '换了内容' }, cardKey);
  check('the same key with other content is refused', reused.status === 409 && reused.json.code === 'SUPPORT_IDEMPOTENCY_KEY_REUSED', reused);
  const question = await send(customer, `/api/v1/conversations/${robotId}/messages`, { text: '物流到哪了？\n今天能到吗' });
  check('a text is sent', question.status === 201 && question.json.seq === 2 && question.json.text === '物流到哪了？\n今天能到吗', question);
  for (const [label, body] of [['both text and card', { text: 'x', card: { type: 'PRODUCT', refId: ORDER, title: 't' } }],
    ['501 characters', { text: '字'.repeat(501) }], ['an unknown card type', { card: { type: 'COUPON', refId: ORDER, title: 't' } }]]) {
    const r = await send(customer, `/api/v1/conversations/${robotId}/messages`, body);
    check(`a message with ${label} is a bad request`, r.status === 400, r);
  }

  // ---- 回复方各看各的 ----
  const merchantList = await call('GET', P, { token: merchant });
  const mine = merchantList.json.items.find((c) => c.conversationId === robotId);
  check('the merchant sees the conversation with two unread', mine && mine.unreadCount === 2 && mine.customer.name === '王女士（示例）'
    && mine.lastMessage.preview === '物流到哪了？ 今天能到吗', mine || merchantList.json);
  check('the merchant sees no phone, address or customer id', !/1[3-9]\d{9}|address|mobile|phone|customerId/.test(merchantList.text.replace(/[0-9a-f-]{36}/g, ''))
    && Object.keys(mine.customer).join() === 'name', mine);
  const providerList = await call('GET', P, { token: provider });
  check('another organization does not see it', !providerList.json.items.some((c) => c.conversationId === robotId), providerList.json);
  for (const [label, token, route] of [['another organization', provider, `${P}/${robotId}/messages`],
    ['the platform support', support, `${S}/${robotId}/messages`]]) {
    const r = await call('GET', route, { token });
    check(`${label} cannot read the merchant conversation`, r.status === 404, r);
  }
  const strangerReply = await send(provider, `${P}/${robotId}/messages`, { text: '不是我的' });
  check('another organization cannot reply', strangerReply.status === 404, strangerReply);

  // ---- 回复和站内信 ----
  const reply1 = await send(merchant, `${P}/${robotId}/messages`, { text: '今天 10:24 已从东营仓发出' });
  const reply2 = await send(merchant, `${P}/${robotId}/messages`, { text: '运单号在订单详情里' });
  check('the merchant replies', reply1.status === 201 && reply2.status === 201 && reply2.json.seq === 4 && reply2.json.mine === true
    && reply2.json.senderSide === 'COUNTERPART', [reply1.json, reply2.json]);
  const customerList = await call('GET', '/api/v1/conversations', { token: customer });
  const seen = customerList.json.items.find((c) => c.conversationId === robotId);
  check('the customer has two unread', seen && seen.unreadCount === 2 && customerList.json.unreadCount >= 2
    && seen.lastMessage.senderSide === 'COUNTERPART', customerList.json);
  let notices = await supportNotices(customer, robotId);
  check('two replies make one unread notification', notices.items.length === 1 && notices.items[0].read === false
    && notices.items[0].title === '商家回复了你' && notices.items[0].eventCode === 'SUPPORT_REPLIED'
    && notices.items[0].target.type === 'CONVERSATION', notices.items);
  check('the notification does not copy the reply', !notices.items[0].body.includes('东营仓') && !notices.items[0].body.includes('运单号'),
    notices.items[0].body);
  const merchantAfter = (await call('GET', P, { token: merchant })).json.items.find((c) => c.conversationId === robotId);
  check('replying clears the merchant side', merchantAfter.unreadCount === 0, merchantAfter);
  const page = await call('GET', `/api/v1/conversations/${robotId}/messages`, { token: customer });
  check('the customer reads the thread oldest first', page.status === 200
    && page.json.items.map((m) => `${m.seq}${m.mine ? 'm' : 't'}`).join() === '1m,2m,3t,4t' && page.json.nextBefore === null, page.json);

  const read = await call('POST', `/api/v1/conversations/${robotId}/read`, { token: customer });
  check('reading clears the customer side', read.status === 200 && read.json.unreadCount === 0, read);
  notices = await supportNotices(customer, robotId);
  check('reading marks the notification read', notices.items.length === 1 && notices.items[0].read === true, notices.items);
  await send(merchant, `${P}/${robotId}/messages`, { text: '还有别的问题吗' });
  notices = await supportNotices(customer, robotId);
  check('a reply after reading makes a new notification', notices.items.length === 2 && notices.items.filter((n) => !n.read).length === 1,
    notices.items);
  check('the notification table keeps one unread per conversation', psql(`SELECT count(*) FROM notification_message
    WHERE category = 'CUSTOMER_SERVICE' AND target_id = '${robotId}' AND read_at IS NULL`) === '1');

  // ---- 平台客服 ----
  const platformId = platform.json.conversationId;
  await send(customer, `/api/v1/conversations/${platformId}/messages`, { text: '我要投诉' });
  const supportList = await call('GET', S, { token: support });
  check('the platform support sees the platform conversation only', supportList.status === 200
    && supportList.json.items.some((c) => c.conversationId === platformId)
    && supportList.json.items.every((c) => c.counterpartKind === 'PLATFORM'), supportList.json);
  const supportReply = await send(support, `${S}/${platformId}/messages`, { text: '请写明投诉对象和经过' });
  check('the platform support replies', supportReply.status === 201, supportReply);
  notices = await supportNotices(customer, platformId);
  check('the customer is told the platform support replied', notices.items.length === 1 && notices.items[0].title === '平台客服回复了你',
    notices.items);
  const merchantOnPlatform = await call('GET', `${P}/${platformId}/messages`, { token: merchant });
  check('a merchant cannot read the platform conversation', merchantOnPlatform.status === 404, merchantOnPlatform);

  // ---- 翻页 ----
  const yunyiId = yunyi.json.conversationId;
  for (let i = 1; i <= 25; i += 1) {
    await send(customer, `/api/v1/conversations/${yunyiId}/messages`, { text: `第 ${i} 条` });
  }
  const seqs = [];
  let before = null;
  for (let round = 0; round < 5; round += 1) {
    const r = await call('GET', `/api/v1/conversations/${yunyiId}/messages?size=10${before ? `&before=${before}` : ''}`, { token: customer });
    seqs.unshift(...r.json.items.map((m) => m.seq));
    before = r.json.nextBefore;
    if (!r.json.hasMore) break;
  }
  check('paging backwards returns every message once in order', seqs.join() === Array.from({ length: 25 }, (_, i) => i + 1).join(), seqs);
  const providerUnread = (await call('GET', P, { token: provider })).json.items.find((c) => c.conversationId === yunyiId);
  check('the provider sees 25 unread', providerUnread && providerUnread.unreadCount === 25, providerUnread);
  const providerRead = await call('POST', `${P}/${yunyiId}/read`, { token: provider });
  check('the provider marks it read', providerRead.status === 200 && providerRead.json.unreadCount === 0, providerRead);

  // ---- 反馈 ----
  const feedbackKey = key();
  const fb = await call('POST', '/api/v1/feedback', { token: customer, headers: { 'Idempotency-Key': feedbackKey }, body: { type: 'DISPLAY', text: '订单页金额被挡住了' } });
  if (fb.json && fb.json.feedbackId) feedback.add(fb.json.feedbackId);
  check('feedback is received', fb.status === 201 && fb.json.type === 'DISPLAY', fb);
  const fbReplay = await call('POST', '/api/v1/feedback', { token: customer, headers: { 'Idempotency-Key': feedbackKey }, body: { type: 'DISPLAY', text: '订单页金额被挡住了' } });
  check('feedback replays with 200', fbReplay.status === 200 && fbReplay.json.feedbackId === fb.json.feedbackId, fbReplay);
  const fbConflict = await call('POST', '/api/v1/feedback', { token: customer, headers: { 'Idempotency-Key': feedbackKey }, body: { type: 'CONTENT', text: '别的' } });
  check('feedback with a reused key is refused', fbConflict.status === 409 && fbConflict.json.code === 'SUPPORT_IDEMPOTENCY_KEY_REUSED', fbConflict);
  const fbBad = await call('POST', '/api/v1/feedback', { token: customer, headers: { 'Idempotency-Key': key() }, body: { type: 'BUG', text: 'x' } });
  check('an unknown feedback type is a bad request', fbBad.status === 400, fbBad);

  // ---- 角色 ----
  for (const [label, token, method, route] of [
    ['the platform support on customer routes', support, 'GET', '/api/v1/conversations'],
    ['the platform support on provider routes', support, 'GET', P],
    ['a merchant on platform routes', merchant, 'GET', S],
    ['a customer on provider routes', customer, 'GET', P],
    ['a customer on platform routes', customer, 'GET', S],
    ['the platform support sending feedback', support, 'POST', '/api/v1/feedback']]) {
    const r = await call(method, route, { token, headers: method === 'POST' ? { 'Idempotency-Key': key() } : {}, body: method === 'POST' ? { type: 'FUNCTION', text: 'x' } : undefined });
    check(`${label} is forbidden`, r.status === 403, r);
  }
  const anonymous = await call('GET', '/api/v1/conversations');
  check('no route works without a login', anonymous.status === 401, anonymous);

  // ---- 库里 ----
  check('the stored customer name is the display name', psql(`SELECT customer_name FROM support_conversation WHERE id = '${robotId}'`) === '王女士（示例）');
  check('messages are counted by sequence', psql(`SELECT message_count || ':' || customer_unread || ':' || counterpart_unread
    FROM support_conversation WHERE id = '${robotId}'`) === '5:1:0');
}

main().then(() => finish(cleanup)(), finish(cleanup));
