#!/usr/bin/env node
'use strict';

// S5-7 企业组织、S5-8 手机号登录与账号的冒烟（契约 contracts/openapi-identity-account.yaml 1.0.0、openapi-trade.yaml 0.6.0）：
// 验证码发送频率、错码作废和号码锁定（爆破）、验证码登录注册、登录设备与退出、换号、企业创建/邀请/角色/移除/退出、
// 切换身份与付款主体、企业订单、注销冷静期（不能下单）与到期匿名化（不能再登录）、个人信息导出与一次性下载。
// 只对本机开发后端（dev 配置：短信是本机模拟服务，验证码从 identity_dev_sms_outbox 读）。
// 注销到期要后端以 IDENTITY_ACCOUNT_JOBS_CLOSURE_INTERVAL_MS=5000（或 --identity.account-jobs.closure-interval-ms=5000）启动。
//   PG_CONTAINER=<容器> BASE_URL=http://127.0.0.1:<端口> node infra/scripts/identity-account-smoke.cjs
// 脚本自己注册的号码都在 199 号段的随机号。跑完（成功、失败都）清掉这次的数据（--keep 保留）：dev-customer 的订单，
// 这次注册的号码账号和它们的个人客户机构、建的企业（成员、邀请）、验证码、模拟短信、号码锁定、登录会话、刷新令牌、注销申请，
// dev-customer 这次申请的个人信息导出（库里的行和本机导出文件）和这次发给它的验证码（S6-04）。
// 导出文件在后端的 IDENTITY_DATA_EXPORT_DIR（开发默认 java.io.tmpdir/lowaltitude-dev-data-exports）；
// 后端和脚本不是同一个用户或临时目录时用 IDENTITY_DATA_EXPORT_DIR 或 S6_JAVA_TMPDIR 指过去。
// dev-customer、dev-provider 自己用密码登录留下的登录会话不删（可能有别的测试正用着同一个账号）。

const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { BUYER, offerId, check, call, psql, login, cleanupBuyer, key, sleep, waitFor, finish } = require('./trade-smoke/lib.cjs');

const KIT_STORE = offerId('101', '3');
const SEED_ENTERPRISE = 'e5000000-0000-4000-8000-000000000001';
const BUYER_PHONE = '13800002026';

// 这次跑建的东西，结束时按它们清理（S6-04）。startedAt 是数据库时间，只用来找这次发给 dev-customer 的验证码和短信。
const run = { startedAt: null, phones: new Set(), users: new Set(), enterprises: new Set(), exports: new Set(), buyerActing: false };
const randomPhone = () => {
  const phone = `199${String(crypto.randomInt(0, 1e8)).padStart(8, '0')}`;
  run.phones.add(phone);
  return phone;
};
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
  run.startedAt = psql('SELECT now()');
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
  check('code login registers a new account and hands out a refresh token', r.status === 200 && r.json.registered === true && r.json.tokenType === 'Bearer' && typeof r.json.refreshToken === 'string' && r.json.refreshToken.length > 30, r.json);
  const tokenA = r.json.accessToken;
  if (tokenA) run.users.add(claims(tokenA).subjectId);
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
  const refreshA2 = second.refreshToken;
  r = await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: refreshA2 } });
  check('a renewal keeps the same device session', r.status === 200 && claims(r.json.accessToken).sid === claims(tokenA2).sid, r.json);
  const renewedA2 = r.json;
  r = await call('GET', '/api/v1/account/sessions', { token: tokenA });
  check('two devices listed, this one first', r.status === 200 && r.json.items.length === 2 && r.json.items[0].current === true && r.json.items[0].deviceName === 'iPhone 15' && r.json.items[1].deviceName === 'Android 手机' && r.json.items[1].cityName === '东营市', r.json);
  const otherSession = r.json.items[1].sessionId;
  r = await call('DELETE', `/api/v1/account/sessions/${claims(tokenA).sid}`, { token: tokenA });
  check('this device is not signed out from the list', r.status === 409 && r.json.code === 'SESSION_IS_CURRENT', r.json);
  r = await call('DELETE', `/api/v1/account/sessions/${otherSession}`, { token: tokenA });
  check('another device is signed out', r.status === 204);
  r = await call('GET', '/api/v1/auth/me', { token: tokenA2 });
  check('the signed-out device token is refused at once', r.status === 401, r.status);
  r = await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: renewedA2.refreshToken } });
  check('the signed-out device cannot renew either', r.status === 401, r.json);
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
  run.users.add(claims(userB.accessToken).subjectId);
  const phoneB = psql(`SELECT u.mobile FROM identity_user u WHERE u.id='${claims(userB.accessToken).subjectId}'`);
  const userC = await phoneLogin(randomPhone(), 'iPhone');
  run.users.add(claims(userC.accessToken).subjectId);
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
  if (ent) run.enterprises.add(ent);
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
  run.buyerActing = true;
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
  psql(`UPDATE identity_sms_code SET sent_at = sent_at - interval '1 day' WHERE phone='${BUYER_PHONE}'`);
  r = await call('POST', '/api/v1/account/sms-codes', { token: customer, body: { purpose: 'EXPORT' } });
  check('export code sent', r.status === 202, r.json);
  r = await call('POST', '/api/v1/account/data-exports', { token: customer, body: { sections: ['ACCOUNT', 'ORDERS'], code: '000001' === latestCode('13800002026') ? '000002' : '000001' } });
  check('wrong code refuses the export', r.status === 400 && r.json.code === 'SMS_CODE_INVALID', r.json);
  r = await call('POST', '/api/v1/account/data-exports', { token: customer, body: { sections: ['ACCOUNT', 'ORDERS', 'ENTERPRISES'], code: latestCode('13800002026') } });
  check('export accepted', r.status === 202 && r.json.status === 'PENDING', r.json);
  const exportId = r.json.exportId;
  if (exportId) run.exports.add(exportId);
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
    run.users.add(claims(again2.accessToken).subjectId);
    check('the number registers a fresh account after closure', again2.registered === true && claims(again2.accessToken).subjectId !== userA);
  }
}

const uuidArray = (ids) => `ARRAY[${[...ids].filter((id) => /^[0-9a-f-]{36}$/.test(id)).map((id) => `'${id}'`).join(',')}]::uuid[]`;
const phoneArray = (phones) => `ARRAY[${[...phones].filter((p) => /^1\d{10}$/.test(p)).map((p) => `'${p}'`).join(',')}]::text[]`;

/**
 * 清掉这次跑建的账号数据（S6-04）。一个事务：任何一行删不掉（比如别的模块有外键指着这些账号）就整个回滚、报清理失败，
 * 不会删一半。只删这次注册的号码账号、它们的个人客户机构（PERSONAL-）和这次建的企业，示例企业和开发账号不动。
 */
function cleanupIdentity() {
  if (!run.startedAt) return;
  const since = `'${run.startedAt}'::timestamptz`;
  const users = `SELECT id FROM identity_user WHERE id = ANY(${uuidArray(run.users)}) OR mobile = ANY(${phoneArray(run.phones)})`;
  const exportsOf = `SELECT id FROM identity_data_export WHERE id = ANY(${uuidArray(run.exports)}) OR user_id IN (${users})`;
  // 先记下导出文件的位置，库里的行删了就找不到了。
  const storageKeys = psql(`SELECT storage_key FROM identity_data_export WHERE id IN (${exportsOf}) AND storage_key IS NOT NULL`)
    .split('\n').filter(Boolean);
  const result = psql(`
    BEGIN;
    CREATE TEMP TABLE smoke_users ON COMMIT DROP AS ${users};
    CREATE TEMP TABLE smoke_orgs ON COMMIT DROP AS
      SELECT o.id FROM identity_organization o
       WHERE o.id = ANY(${uuidArray(run.enterprises)})
          OR (o.organization_code LIKE 'PERSONAL-%' AND (
                o.id IN (SELECT default_organization_id FROM identity_user WHERE id IN (SELECT id FROM smoke_users))
             OR o.id IN (SELECT organization_id FROM identity_organization_member WHERE user_id IN (SELECT id FROM smoke_users))));
    CREATE TEMP TABLE smoke_exports ON COMMIT DROP AS ${exportsOf};
    DELETE FROM identity_data_export WHERE id IN (SELECT id FROM smoke_exports);
    DELETE FROM identity_refresh_token WHERE user_id IN (SELECT id FROM smoke_users);
    DELETE FROM identity_login_session WHERE user_id IN (SELECT id FROM smoke_users);
    DELETE FROM identity_closure_request WHERE user_id IN (SELECT id FROM smoke_users);
    -- 验证码、模拟短信、号码锁定按号码记；dev-customer 的只删这次发的（冒烟把发送时间往前挪过一天，按过期时间找）。
    DELETE FROM identity_sms_code WHERE user_id IN (SELECT id FROM smoke_users) OR phone = ANY(${phoneArray(run.phones)})
       OR (phone = '${BUYER_PHONE}' AND expires_at >= ${since});
    DELETE FROM identity_dev_sms_outbox WHERE phone = ANY(${phoneArray(run.phones)}) OR (phone = '${BUYER_PHONE}' AND sent_at >= ${since});
    DELETE FROM identity_phone_guard WHERE phone = ANY(${phoneArray(run.phones)});
    DELETE FROM identity_enterprise_invitation WHERE organization_id IN (SELECT id FROM smoke_orgs)
       OR invited_by IN (SELECT id FROM smoke_users) OR responded_by IN (SELECT id FROM smoke_users);
    DELETE FROM identity_enterprise_member WHERE organization_id IN (SELECT id FROM smoke_orgs)
       OR user_id IN (SELECT id FROM smoke_users) OR ended_by IN (SELECT id FROM smoke_users);
    DELETE FROM identity_enterprise WHERE organization_id IN (SELECT id FROM smoke_orgs);
    DELETE FROM identity_user_role WHERE user_id IN (SELECT id FROM smoke_users) OR organization_id IN (SELECT id FROM smoke_orgs);
    DELETE FROM identity_organization_member WHERE user_id IN (SELECT id FROM smoke_users)
       OR organization_id IN (SELECT id FROM smoke_orgs);
    DELETE FROM identity_auth_audit WHERE actor_user_id IN (SELECT id FROM smoke_users)
       OR organization_id IN (SELECT id FROM smoke_orgs) OR target_id IN (SELECT id FROM smoke_users);
    -- 中途失败时 dev-customer 可能还停在示例企业身份上，改回个人（开发种子的样子）。
    ${run.buyerActing ? `UPDATE identity_user SET acting_organization_id = NULL WHERE id = '${BUYER}';` : ''}
    DELETE FROM identity_user WHERE id IN (SELECT id FROM smoke_users);
    DELETE FROM identity_organization WHERE id IN (SELECT id FROM smoke_orgs);
    SELECT 'smoke users ' || (SELECT count(*) FROM smoke_users) || ', orgs ' || (SELECT count(*) FROM smoke_orgs)
      || ', exports ' || (SELECT count(*) FROM smoke_exports);
    COMMIT;
  `);
  const exportDir = process.env.IDENTITY_DATA_EXPORT_DIR
    || path.join(process.env.S6_JAVA_TMPDIR || os.tmpdir(), 'lowaltitude-dev-data-exports');
  let removed = 0;
  for (const storageKey of storageKeys) {
    if (!/^data-exports\/[0-9a-f-]{36}\/[0-9a-f-]{36}\.zip$/.test(storageKey)) continue;
    const file = path.join(exportDir, storageKey);
    if (fs.existsSync(file)) {
      fs.unlinkSync(file);
      removed += 1;
    }
    const folder = path.dirname(file);
    if (fs.existsSync(folder) && fs.readdirSync(folder).length === 0) fs.rmdirSync(folder);
  }
  const summary = (result.split('\n').find((line) => line.startsWith('smoke users')) || '').trim();
  console.log(`清理：${summary}；导出文件 ${storageKeys.length} 个，本机删掉 ${removed} 个${removed < storageKeys.length ? `（没找到的在 ${exportDir} 以外，后端换了目录时用 IDENTITY_DATA_EXPORT_DIR 指过去）` : ''}`);
}

// 订单先删（企业身份下的订单记着付款主体），再删账号。
const cleanup = () => {
  cleanupBuyer();
  cleanupIdentity();
};
main().then(() => finish(cleanup)(), (error) => finish(cleanup)(error));
