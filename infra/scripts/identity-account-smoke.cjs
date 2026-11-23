#!/usr/bin/env node
'use strict';

// S5-7 企业组织、S5-8 手机号登录与账号的冒烟（契约 contracts/openapi-identity-account.yaml 1.0.0、openapi-trade.yaml 0.6.0）：
// 验证码发送频率、错码作废和号码锁定（爆破）、验证码登录注册、登录设备与退出、换号、企业创建/邀请/角色/移除/退出、
// 切换身份与付款主体、企业订单、注销冷静期（不能下单）与到期匿名化（不能再登录）、个人信息导出与一次性下载。
// 只对本机开发后端（dev 配置：短信是本机模拟服务，验证码从 identity_dev_sms_outbox 读）。
// 注销到期要后端以 IDENTITY_ACCOUNT_JOBS_CLOSURE_INTERVAL_MS=5000（或 --identity.account-jobs.closure-interval-ms=5000）启动。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> node infra/scripts/identity-account-smoke.cjs
// 脚本自己注册的号码都在 199 号段的随机号；跑完清掉 dev-customer 的订单（--keep 保留）。

const crypto = require('node:crypto');
const { offerId, check, call, psql, login, cleanupBuyer, key, sleep, waitFor, finish } = require('./trade-smoke/lib.cjs');

const KIT_STORE = offerId('101', '3');
const SEED_ENTERPRISE = 'e5000000-0000-4000-8000-000000000001';
const randomPhone = () => `199${String(crypto.randomInt(0, 1e8)).padStart(8, '0')}`;
const latestCode = (phone) => {
  const content = psql(`SELECT content FROM identity_dev_sms_outbox WHERE phone='${phone}' ORDER BY sent_at DESC LIMIT 1`);
  const match = /验证码 (\d{6})/.exec(content);
  return match ? match[1] : null;
};
const claims = (token) => JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
const device = (name) => ({ deviceKey: crypto.randomUUID(), deviceName: name, platform: 'IOS', cityCode: '370500' });

async function phoneLogin(phone, name = 'iPhone 15') {
  const sent = await call('POST', '/api/v1/auth/sms-codes', { body: { phone } });
  if (sent.status !== 202) throw new Error(`发码失败 ${sent.status} ${sent.text}`);
  const r = await call('POST', '/api/v1/auth/phone-login', { body: { phone, code: latestCode(phone), device: device(name) } });
  if (r.status !== 200) throw new Error(`验证码登录失败 ${r.status} ${r.text}`);
  return r.json;
}

async function main() {
  // ---------------------------------------------------------------- 验证码与登录
  const phoneA = randomPhone();
  let r = await call('POST', '/api/v1/auth/sms-codes', { body: { phone: '12345' } });
  check('bad phone is 400', r.status === 400 && r.json.code === 'COMMON_INVALID_ARGUMENT', r.json);
  r = await call('POST', '/api/v1/auth/sms-codes', { body: { phone: phoneA } });
  check('login code accepted', r.status === 202 && r.json.maskedPhone === `${phoneA.slice(0, 3)}****${phoneA.slice(7)}` && r.json.resendAfterSeconds === 60, r.json);
  check('fake sms writes the outbox and no real number', /^\d{6}$/.test(latestCode(phoneA) || ''));
  r = await call('POST', '/api/v1/auth/sms-codes', { body: { phone: phoneA } });
  check('resend within 60s is 429', r.status === 429 && r.json.code === 'SMS_TOO_FREQUENT', r.json);
  r = await call('POST', '/api/v1/auth/phone-login', { body: { phone: phoneA, code: '000000' === latestCode(phoneA) ? '111111' : '000000' } });
  check('wrong code is 400', r.status === 400 && r.json.code === 'SMS_CODE_INVALID', r.json);
  const codeA = latestCode(phoneA);
  r = await call('POST', '/api/v1/auth/phone-login', { body: { phone: phoneA, code: codeA, device: device('iPhone 15') } });
  check('code login registers a new account', r.status === 200 && r.json.registered === true && r.json.tokenType === 'Bearer', r.json);
  const tokenA = r.json.accessToken;
  check('token carries a session id and no enterprise identity', !!claims(tokenA).sid && !('identity' in claims(tokenA)));
  r = await call('POST', '/api/v1/auth/phone-login', { body: { phone: phoneA, code: codeA } });
  check('a used code does not work twice', r.status === 400 && r.json.code === 'SMS_CODE_INVALID', r.json);
  r = await call('GET', '/api/v1/auth/me', { token: tokenA });
  check('me shows masked phone and personal identity', r.status === 200 && r.json.maskedPhone === `${phoneA.slice(0, 3)}****${phoneA.slice(7)}` && r.json.identity.type === 'PERSONAL' && r.json.roles.includes('CUSTOMER') && r.json.displayName === `用户${phoneA.slice(7)}`, r.json);
  check('new account has its own personal customer organization', psql(`SELECT o.organization_type FROM identity_user u JOIN identity_organization o ON o.id=u.default_organization_id WHERE u.mobile='${phoneA}'`) === 'CUSTOMER');

  // 爆破：一条码错 5 次作废，1 小时内错 10 次锁号码
  const phoneBrute = randomPhone();
  await call('POST', '/api/v1/auth/sms-codes', { body: { phone: phoneBrute } });
  const realCode = latestCode(phoneBrute);
  const wrong = realCode === '999999' ? '888888' : '999999';
  for (let i = 0; i < 5; i++) await call('POST', '/api/v1/auth/phone-login', { body: { phone: phoneBrute, code: wrong } });
  r = await call('POST', '/api/v1/auth/phone-login', { body: { phone: phoneBrute, code: realCode } });
  check('after 5 wrong tries the right code no longer works', r.status === 400 && r.json.code === 'SMS_CODE_INVALID', r.json);
  for (let i = 0; i < 4; i++) await call('POST', '/api/v1/auth/phone-login', { body: { phone: phoneBrute, code: wrong } });
  r = await call('POST', '/api/v1/auth/phone-login', { body: { phone: phoneBrute, code: realCode } });
  check('10 failures lock the number', r.status === 429 && r.json.code === 'PHONE_LOCKED', r.json);
  r = await call('POST', '/api/v1/auth/sms-codes', { body: { phone: phoneBrute } });
  check('a locked number gets no new code', r.status === 429 && r.json.code === 'PHONE_LOCKED', r.json);
  check('only code hashes are stored', psql(`SELECT count(*) FROM identity_sms_code WHERE phone='${phoneBrute}' AND code_hash='${realCode}'`) === '0');

  // ---------------------------------------------------------------- 登录设备
  psql(`UPDATE identity_sms_code SET sent_at = sent_at - interval '1 day' WHERE phone='${phoneA}'`);
  const second = await phoneLogin(phoneA, 'Android 手机');
  check('logging in again on another device keeps the same account', second.registered === false);
  const tokenA2 = second.accessToken;
  r = await call('GET', '/api/v1/account/sessions', { token: tokenA });
  check('two devices listed, this one first', r.status === 200 && r.json.items.length === 2 && r.json.items[0].current === true && r.json.items[0].deviceName === 'iPhone 15' && r.json.items[1].deviceName === 'Android 手机' && r.json.items[1].cityName === '东营市', r.json);
  const otherSession = r.json.items[1].sessionId;
  r = await call('DELETE', `/api/v1/account/sessions/${claims(tokenA).sid}`, { token: tokenA });
  check('this device is not signed out from the list', r.status === 409 && r.json.code === 'SESSION_IS_CURRENT', r.json);
  r = await call('DELETE', `/api/v1/account/sessions/${otherSession}`, { token: tokenA });
  check('another device is signed out', r.status === 204);
  r = await call('GET', '/api/v1/auth/me', { token: tokenA2 });
  check('the signed-out device token is refused at once', r.status === 401, r.status);
  r = await call('DELETE', `/api/v1/account/sessions/${crypto.randomUUID()}`, { token: tokenA });
  check('unknown session is 404', r.status === 404);

  // ---------------------------------------------------------------- 换号
  const phoneA2 = randomPhone();
  r = await call('POST', '/api/v1/account/sms-codes', { token: tokenA, body: { purpose: 'BIND_NEW', phone: '13800002026' } });
  check('a number bound to another account is refused', r.status === 409 && r.json.code === 'PHONE_TAKEN', r.json);
  r = await call('PUT', '/api/v1/account/phone', { token: tokenA, body: { newPhone: phoneA2, code: '123456' } });
  check('changing without the ticket is refused', r.status === 400 && r.json.code === 'PHONE_TICKET_INVALID', r.json);
  r = await call('POST', '/api/v1/account/sms-codes', { token: tokenA, body: { purpose: 'VERIFY_CURRENT' } });
  check('code to the current number', r.status === 202, r.json);
  r = await call('POST', '/api/v1/account/phone/verifications', { token: tokenA, body: { code: latestCode(phoneA) } });
  check('current number verified, ticket issued', r.status === 200 && r.json.changeTicket.length >= 32 && r.json.expiresInSeconds === 600, r.json);
  const ticket = r.json.changeTicket;
  const thirdLogin = await (async () => {
    psql(`UPDATE identity_sms_code SET sent_at = sent_at - interval '1 day' WHERE phone='${phoneA}'`);
    return phoneLogin(phoneA, 'iPad');
  })();
  r = await call('POST', '/api/v1/account/sms-codes', { token: tokenA, body: { purpose: 'BIND_NEW', phone: phoneA2 } });
  check('code to the new number', r.status === 202, r.json);
  r = await call('PUT', '/api/v1/account/phone', { token: tokenA, body: { newPhone: phoneA2, code: latestCode(phoneA2), changeTicket: ticket } });
  check('phone changed and other devices signed out', r.status === 200 && r.json.maskedPhone === `${phoneA2.slice(0, 3)}****${phoneA2.slice(7)}` && r.json.signedOutSessions === 1, r.json);
  check('the other device is out after the change', (await call('GET', '/api/v1/auth/me', { token: thirdLogin.accessToken })).status === 401);
  check('this device stays in', (await call('GET', '/api/v1/auth/me', { token: tokenA })).status === 200);

  // ---------------------------------------------------------------- 企业组织
  const userB = await phoneLogin(randomPhone(), 'Android 手机');
  const phoneB = psql(`SELECT u.mobile FROM identity_user u WHERE u.id='${claims(userB.accessToken).subjectId}'`);
  const userC = await phoneLogin(randomPhone(), 'iPhone');
  const provider = await login('dev-provider');
  r = await call('GET', '/api/v1/account/enterprises', { token: provider });
  check('enterprises are for customers only', r.status === 403, r.status);
  r = await call('POST', '/api/v1/account/enterprises', { token: tokenA, headers: { 'Idempotency-Key': key() }, body: { name: '示例企业', creditCode: '91370500EXAMPLE26I' } });
  check('credit code format is checked', r.status === 400, r.json);
  r = await call('POST', '/api/v1/account/enterprises', { token: tokenA, headers: { 'Idempotency-Key': key() }, body: { name: '另一家', creditCode: '91370500EXAMPLE26X' } });
  check('a credit code already used is refused', r.status === 409 && r.json.code === 'ENTERPRISE_CREDIT_CODE_TAKEN', r.json);
  const creditCode = `9137${String(crypto.randomInt(0, 1e8)).padStart(8, '0')}TEAM${String(crypto.randomInt(0, 100)).padStart(2, '0')}`;
  const createKey = key();
  r = await call('POST', '/api/v1/account/enterprises', { token: tokenA, headers: { 'Idempotency-Key': createKey }, body: { name: '冒烟测试物流', creditCode, cityCode: '370500' } });
  check('enterprise created, creator is admin', r.status === 201 && r.json.role === 'ADMIN' && r.json.memberCount === 1 && r.json.cityName === '东营市' && r.json.maskedCreditCode === `${creditCode.slice(0, 4)}************${creditCode.slice(16)}`, r.json);
  const ent = r.json.enterpriseId;
  r = await call('POST', '/api/v1/account/enterprises', { token: tokenA, headers: { 'Idempotency-Key': createKey }, body: { name: '冒烟测试物流', creditCode, cityCode: '370500' } });
  check('replay returns the same enterprise', r.status === 201 && r.json.enterpriseId === ent, r.json);
  r = await call('POST', '/api/v1/account/enterprises', { token: tokenA, headers: { 'Idempotency-Key': createKey }, body: { name: '改了名字', creditCode, cityCode: '370500' } });
  check('same key, other content is 409', r.status === 409 && r.json.code === 'IDEMPOTENCY_CONFLICT', r.json);
  r = await call('GET', `/api/v1/account/enterprises/${ent}`, { token: userB.accessToken });
  check('outsiders see 404', r.status === 404, r.json);

  r = await call('POST', `/api/v1/account/enterprises/${ent}/invitations`, { token: tokenA, body: { kind: 'PHONE', phone: phoneB } });
  check('phone invitation created', r.status === 201 && r.json.invitation.kind === 'PHONE' && r.json.inviteCode === null && r.json.invitation.role === 'MEMBER', r.json);
  const phoneInvite = r.json.invitation.invitationId;
  r = await call('GET', `/api/v1/account/enterprise-invitations/${phoneInvite}`, { token: userC.accessToken });
  check('someone else cannot read a phone invitation', r.status === 404);
  r = await call('GET', '/api/v1/account/enterprises', { token: userB.accessToken });
  check('invitee sees it in their list', r.status === 200 && r.json.invitations.some((i) => i.invitationId === phoneInvite && i.enterpriseName === '冒烟测试物流' && i.status === 'PENDING'), r.json);
  r = await call('POST', `/api/v1/account/enterprise-invitations/${phoneInvite}/acceptance`, { token: userB.accessToken });
  check('invitee joins', r.status === 200 && r.json.role === 'MEMBER' && r.json.memberCount === 2, r.json);
  r = await call('POST', `/api/v1/account/enterprise-invitations/${phoneInvite}/acceptance`, { token: userB.accessToken });
  check('accepting twice is refused', r.status === 409 && r.json.code === 'INVITATION_NOT_PENDING', r.json);

  r = await call('POST', `/api/v1/account/enterprises/${ent}/invitations`, { token: userB.accessToken, body: { kind: 'LINK' } });
  check('a member can create a link invitation', r.status === 201 && r.json.inviteCode && r.json.inviteCode.length >= 16, r.json);
  const linkInvite = r.json.invitation.invitationId;
  const linkCode = r.json.inviteCode;
  check('the link code is stored only as a hash', psql(`SELECT count(*) FROM identity_enterprise_invitation WHERE code_hash='${linkCode}'`) === '0');
  r = await call('GET', `/api/v1/account/enterprise-invitations/${linkInvite}?code=wrongwrongwrongwrong`, { token: userC.accessToken });
  check('wrong link code is 404', r.status === 404);
  psql(`UPDATE identity_enterprise_invitation SET sent_at = now() - interval '8 days', expires_at = now() - interval '1 day' WHERE id='${linkInvite}'`);
  r = await call('POST', `/api/v1/account/enterprise-invitations/${linkInvite}/acceptance`, { token: userC.accessToken, body: { code: linkCode } });
  check('an expired invitation cannot be accepted', r.status === 409 && r.json.code === 'INVITATION_EXPIRED', r.json);
  r = await call('POST', `/api/v1/account/enterprises/${ent}/invitations`, { token: tokenA, body: { kind: 'LINK' } });
  const link2 = r.json;
  r = await call('GET', `/api/v1/account/enterprise-invitations/${link2.invitation.invitationId}?code=${encodeURIComponent(link2.inviteCode)}`, { token: userC.accessToken });
  check('link invitation readable with its code', r.status === 200 && r.json.kind === 'LINK' && r.json.status === 'PENDING', r.json);
  r = await call('POST', `/api/v1/account/enterprise-invitations/${link2.invitation.invitationId}/decline`, { token: userC.accessToken, body: { code: link2.inviteCode } });
  check('link invitation declined', r.status === 200 && r.json.status === 'DECLINED', r.json);

  r = await call('GET', `/api/v1/account/enterprises/${ent}/members`, { token: userB.accessToken });
  check('members list masks phones and marks self', r.status === 200 && r.json.myRole === 'MEMBER' && r.json.members.length === 2 && r.json.members[0].role === 'ADMIN' && r.json.members.every((m) => /^\d{3}\*{4}\d{4}$/.test(m.maskedPhone)) && r.json.members[1].self, r.json);
  const memberA = r.json.members[0].memberId;
  const memberB = r.json.members[1].memberId;
  r = await call('DELETE', `/api/v1/account/enterprises/${ent}/members/${memberA}`, { token: userB.accessToken });
  check('a member cannot remove anyone', r.status === 403 && r.json.code === 'ENTERPRISE_ADMIN_REQUIRED', r.json);
  r = await call('PUT', `/api/v1/account/enterprises/${ent}/members/${memberA}`, { token: tokenA, body: { role: 'MEMBER' } });
  check('the last admin cannot step down', r.status === 409 && r.json.code === 'ENTERPRISE_LAST_ADMIN', r.json);
  r = await call('DELETE', `/api/v1/account/enterprises/${ent}/membership`, { token: tokenA });
  check('the last admin cannot leave while others stay', r.status === 409 && r.json.code === 'ENTERPRISE_LAST_ADMIN', r.json);

  // 切换身份
  r = await call('PUT', '/api/v1/account/identity', { token: userB.accessToken, body: { enterpriseId: SEED_ENTERPRISE } });
  check('cannot switch to an enterprise you are not in', r.status === 404, r.json);
  r = await call('PUT', '/api/v1/account/identity', { token: userB.accessToken, body: { enterpriseId: ent } });
  check('switch to the enterprise reissues the token', r.status === 200 && r.json.identity.type === 'ENTERPRISE' && r.json.identity.role === 'MEMBER' && claims(r.json.accessToken).identity === ent && claims(r.json.accessToken).sid === claims(userB.accessToken).sid, r.json);
  const tokenBEnt = r.json.accessToken;
  r = await call('GET', '/api/v1/auth/me', { token: tokenBEnt });
  check('me shows the enterprise identity', r.json.identity.type === 'ENTERPRISE' && r.json.identity.enterpriseName === '冒烟测试物流', r.json);
  r = await call('DELETE', `/api/v1/account/enterprises/${ent}/members/${memberB}`, { token: tokenA });
  check('admin removes a member', r.status === 204);
  r = await call('GET', '/api/v1/auth/me', { token: tokenBEnt });
  check('a removed member falls back to personal', r.json.identity.type === 'PERSONAL', r.json);
  r = await call('GET', `/api/v1/trade/enterprise-orders?enterpriseId=${ent}`, { token: tokenBEnt });
  check('a removed member cannot read enterprise orders', r.status === 404, r.json);

  // ---------------------------------------------------------------- 付款主体（dev-customer 是示例企业的管理员）
  cleanupBuyer();
  let customer = await login('dev-customer');
  r = await call('GET', '/api/v1/auth/me', { token: customer });
  check('dev-customer shows the seeded masked phone', r.json.maskedPhone === '138****2026', r.json);
  r = await call('PUT', '/api/v1/account/identity', { token: customer, body: { enterpriseId: SEED_ENTERPRISE } });
  check('dev-customer switches to the sample enterprise', r.status === 200, r.json);
  customer = r.json.accessToken;
  const pickupBody = { cityCode: '370500', offerId: KIT_STORE, quantity: 1, deliveryMode: 'PICKUP', expectedPayableMinor: 138000, agreementAccepted: true };
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: pickupBody });
  check('order under the enterprise records the payer', r.status === 201 && r.json.payer.type === 'ENTERPRISE' && r.json.payer.name === '东营港务物流（示例）', JSON.stringify(r.json).slice(0, 400));
  const entOrder = r.json.orderId;
  r = await call('GET', `/api/v1/trade/enterprise-orders?enterpriseId=${SEED_ENTERPRISE}`, { token: customer });
  check('enterprise orders list it with the operator', r.status === 200 && r.json.items[0].orderId === entOrder && r.json.items[0].operatorName === '王女士（示例）' && r.json.counts.pendingPayment >= 1, r.json);
  r = await call('GET', `/api/v1/trade/enterprise-orders?enterpriseId=${SEED_ENTERPRISE}`, { token: tokenA });
  check('outsiders cannot list enterprise orders', r.status === 404, r.json);
  r = await call('GET', '/api/v1/account/closure', { token: customer });
  check('open order blocks closure', r.status === 200 && r.json.conditions.find((c) => c.code === 'NO_OPEN_ORDERS').met === false, r.json);
  r = await call('PUT', '/api/v1/account/identity', { token: customer, body: { enterpriseId: null } });
  customer = r.json.accessToken;
  r = await call('POST', '/api/v1/trade/orders', { token: customer, headers: { 'Idempotency-Key': key() }, body: pickupBody });
  check('order as a person records PERSONAL', r.status === 201 && r.json.payer.type === 'PERSONAL' && r.json.payer.name === null, r.json);

  // ---------------------------------------------------------------- 个人信息导出
  r = await call('GET', '/api/v1/account/data-exports', { token: customer });
  check('export overview lists sections and counts', r.status === 200 && r.json.sections.map((s) => s.code).join() === 'ACCOUNT,PARTICIPANTS,ADDRESSES,ORDERS,ENTERPRISES' && r.json.sections.find((s) => s.code === 'ORDERS').count === 2, r.json);
  // 脚本可能一天里跑好几次：把这个号码之前发的码挪到前一天，不撞 60 秒和每天 10 条的限制。
  psql(`UPDATE identity_sms_code SET sent_at = sent_at - interval '1 day' WHERE phone='13800002026'`);
  r = await call('POST', '/api/v1/account/sms-codes', { token: customer, body: { purpose: 'EXPORT' } });
  check('export code sent', r.status === 202, r.json);
  r = await call('POST', '/api/v1/account/data-exports', { token: customer, body: { sections: ['ACCOUNT', 'ORDERS'], code: '000001' === latestCode('13800002026') ? '000002' : '000001' } });
  check('wrong code refuses the export', r.status === 400 && r.json.code === 'SMS_CODE_INVALID', r.json);
  r = await call('POST', '/api/v1/account/data-exports', { token: customer, body: { sections: ['ACCOUNT', 'ORDERS', 'ENTERPRISES'], code: latestCode('13800002026') } });
  check('export accepted', r.status === 202 && r.json.status === 'PENDING', r.json);
  const exportId = r.json.exportId;
  await waitFor(() => psql(`SELECT status FROM identity_data_export WHERE id='${exportId}'`) !== 'PENDING', 60);
  r = await call('GET', '/api/v1/account/data-exports', { token: customer });
  check('export generated', r.json.exports[0].exportId === exportId && r.json.exports[0].status === 'READY' && r.json.exports[0].sizeBytes > 0, r.json.exports);
  r = await call('POST', `/api/v1/account/data-exports/${exportId}/download-intents`, { token: tokenA });
  check('someone else cannot download it', r.status === 404, r.json);
  r = await call('POST', `/api/v1/account/data-exports/${exportId}/download-intents`, { token: customer });
  check('download intent is a relative one-time link', r.status === 201 && r.json.downloadUrl.startsWith(`/api/v1/account/data-exports/${exportId}/file?token=`) && !r.json.downloadUrl.includes('data-exports/1111'), r.json);
  const url = r.json.downloadUrl;
  const file = await fetch(new URL(url, process.env.BASE_URL || 'http://127.0.0.1:8080'));
  const bytes = Buffer.from(await file.arrayBuffer());
  check('file downloads without a login header', file.status === 200 && file.headers.get('content-type') === 'application/zip' && bytes.subarray(0, 2).toString() === 'PK' && bytes.includes(Buffer.from('orders.json')) && bytes.includes(Buffer.from('manifest.json')), file.status);
  const again = await fetch(new URL(url, process.env.BASE_URL || 'http://127.0.0.1:8080'));
  check('the link works once', again.status === 404, again.status);

  // ---------------------------------------------------------------- 注销
  r = await call('GET', '/api/v1/account/closure', { token: tokenA });
  check('sole remaining admin of a one-person enterprise may close', r.status === 200 && r.json.conditions.find((c) => c.code === 'NO_ENTERPRISE_ADMIN').met === true, r.json);
  r = await call('POST', '/api/v1/account/closure', { token: tokenA });
  check('closure requested, 15 days to go', r.status === 201 && r.json.status === 'PENDING' && Math.round((new Date(r.json.dueAt) - new Date(r.json.requestedAt)) / 86_400_000) === 15, r.json);
  r = await call('POST', '/api/v1/account/closure', { token: tokenA });
  check('requesting twice is refused', r.status === 409 && r.json.code === 'CLOSURE_ALREADY_PENDING', r.json);
  r = await call('POST', '/api/v1/trade/orders', { token: tokenA, headers: { 'Idempotency-Key': key() }, body: pickupBody });
  check('no orders during the cooling-off', r.status === 409 && r.json.code === 'TRADE_ACCOUNT_CLOSING', r.json);
  r = await call('DELETE', '/api/v1/account/closure', { token: tokenA });
  check('closure withdrawn', r.status === 200 && r.json.status === 'NONE', r.json);
  r = await call('POST', '/api/v1/account/closure', { token: tokenA });
  check('closure requested again', r.status === 201, r.json);
  const userA = claims(tokenA).subjectId;
  psql(`UPDATE identity_closure_request SET requested_at = now() - interval '16 days', due_at = now() - interval '1 minute' WHERE user_id='${userA}' AND status='PENDING'`);
  const closed = await waitFor(() => psql(`SELECT status FROM identity_user WHERE id='${userA}'`) === 'CLOSED', 90);
  check('closure completes when due (needs a short closure interval)', closed);
  if (closed) {
    check('closed account is anonymized', psql(`SELECT display_name || '|' || coalesce(mobile, '-') || '|' || (login_name LIKE 'closed-%') FROM identity_user WHERE id='${userA}'`) === '已注销用户|-|true');
    check('closed account token is refused', (await call('GET', '/api/v1/auth/me', { token: tokenA })).status === 401);
    check('closed account left its enterprise (dissolved, it was alone)', psql(`SELECT status FROM identity_enterprise WHERE organization_id='${ent}'`) === 'DISSOLVED');
    const again2 = await phoneLogin(phoneA2);
    check('the number registers a fresh account after closure', again2.registered === true && claims(again2.accessToken).subjectId !== userA);
  }
}

main().then(() => finish(() => cleanupBuyer())(), (error) => finish(() => cleanupBuyer())(error));
