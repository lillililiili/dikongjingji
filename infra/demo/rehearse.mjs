// 演示前的自动彩排：照 docs/ops/现场演示.md 的脚本，用演示包的 H5 和运营端真走一遍，付款点“模拟付款”，不敲命令。
//   node infra/demo/rehearse.mjs [1 2 3 4 5 8]   不带参数跑全部；截图在 .runtime/demo/shots/
// 真机上的扫码、安装包不在里面，要接手机走。
import { createRequire } from 'node:module';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

import { PORTS, REPO, STATE, readEnv } from './config.mjs';

const require = createRequire(path.join(REPO, 'apps/mobile/package.json'));
const { chromium, expect } = require('@playwright/test');
const API = `http://127.0.0.1:${PORTS.backend}`;
const ADMIN = `http://127.0.0.1:${PORTS.admin}`;
const H5 = `http://127.0.0.1:${PORTS.h5}`;
const CITY = '370500';
const SHOTS = path.join(STATE, 'shots');
const SEED = fs.readFileSync(path.join(REPO, 'backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql'), 'utf8');
const PASSWORD = /crypt\('([^']+)'/.exec(SEED)[1];
const OPERATOR_PASSWORD = readEnv(path.join(STATE, 'stack.env')).DEV_OPERATOR_PASSWORD;
const wanted = process.argv.slice(2);
fs.mkdirSync(SHOTS, { recursive: true });

async function api(method, route, token, body, headers = {}) {
  const response = await fetch(API + route, { method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers }, body: body ? JSON.stringify(body) : undefined });
  const json = await response.json().catch(() => null);
  if (!response.ok) throw new Error(`${method} ${route} → ${response.status} ${json?.code}`);
  return json;
}
const login = async (name, password = PASSWORD) => (await api('POST', '/api/v1/auth/login', null, { loginName: name, password })).accessToken;

const browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome' });
const log = (text) => console.log(`  ${new Date().toTimeString().slice(0, 8)} ${text}`);
const tap = (page, id) => page.getByTestId(id).click();

async function openApp(name) {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  page.setDefaultTimeout(20_000);
  // 首次启动的隐私同意和定位引导在真机上点一次就不再出现，这里直接记为已同意。
  await page.addInitScript(() => localStorage.setItem('account.privacyConsentAt', '2026-11-24T00:00:00Z'));
  if (name) await page.addInitScript((token) => localStorage.setItem('inspection.accessToken', token), await login(name));
  await page.goto(`${H5}/`);
  await page.waitForTimeout(800);
  return page;
}
let visits = 0;
const go = async (page, hash) => { visits += 1; await page.goto(`${H5}/?v=${visits}#${hash}`); };
async function openAdmin(name, target, password = PASSWORD) {
  const page = await (await browser.newContext({ viewport: { width: 1440, height: 900 } })).newPage();
  page.setDefaultTimeout(20_000);
  await page.goto(ADMIN + target);
  await page.locator('input[autocomplete="username"]').fill(name);
  await page.locator('input[autocomplete="current-password"]').fill(password);
  await page.locator('form').getByRole('button', { name: '登录' }).click();
  await page.waitForURL((url) => url.pathname === target);
  return page;
}
const shot = (page, name) => page.screenshot({ path: path.join(SHOTS, `${name}.png`) });
const dialog = (page) => page.locator('.el-dialog:visible');
async function confirmBox(page) {
  const box = page.locator('.el-message-box:visible');
  await box.waitFor({ timeout: 2500 }).catch(() => undefined);
  if (await box.count()) await box.locator('.el-button--primary').click();
}
async function success(page) { await page.locator('.el-message--success').last().waitFor(); }
async function modal(page) { const box = page.locator('uni-modal'); await box.waitFor(); await box.locator('.uni-modal__btn_primary').click(); }
/** 收银台：点确认支付 → “模拟微信支付”弹窗确认 → 等支付结果页显示已付款。 */
async function payHere(page) {
  await expect(page.getByTestId('cashier-method-note')).toContainText('演示环境');
  await tap(page, 'cashier-pay');
  await expect(page.locator('uni-modal')).toContainText('模拟微信支付');
  await modal(page);
  await page.getByTestId('pay-result-paid').waitFor();
}
const sample = (name) => path.join(STATE, 'samples', name);
const credit = () => {
  const A = '0123456789ABCDEFGHJKLMNPQRTUWXY'; const W = [1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28];
  const s = `91370500MA${Array.from({ length: 7 }, () => A[crypto.randomInt(31)]).join('')}`;
  return s + A[(31 - W.reduce((t, w, i) => t + w * A.indexOf(s[i]), 0) % 31) % 31];
};
let goodsOrderNo = null;

const items = {
  async 1() {
    const app = await openApp('dev-customer');
    await tap(app, 'home-channel-service');
    await app.getByTestId('inspection-portal').waitFor();
    await tap(app, 'portal-category-power');
    await app.locator('[data-testid^="catalog-service-"]').first().click();
    await app.getByTestId('inspection-detail').waitFor();
    await app.waitForTimeout(1500);
    await app.getByText('立即预约').last().click();
    await app.waitForURL(/inspection\/booking/);
    await tap(app, 'booking-location');
    await tap(app, 'map-pick-place-road-88');
    await tap(app, 'map-pick-confirm');
    await app.waitForURL(/inspection\/booking/);
    const inputs = app.locator('.booking-row__input input');
    await inputs.nth(0).fill('12');
    await app.locator('.booking-row--time').click();
    const sheet = app.locator('.inspection-time-sheet__panel');
    await sheet.locator('.inspection-time-sheet__date').nth(1).click();
    await app.waitForTimeout(1200);
    await sheet.locator('.inspection-time-sheet__slot:not(.inspection-time-sheet__slot--full)').first().click();
    await sheet.locator('.inspection-time-sheet__confirm').click();
    await app.locator('.booking-item').first().click();
    await inputs.nth(1).fill('张验证');
    await inputs.nth(2).fill('13900001234');
    await tap(app, 'booking-agreement');
    await app.locator('.inspection-booking__submit').click();
    await app.getByTestId('booking-submitted-status').waitFor();
    const reservationId = new URLSearchParams(new URL(app.url()).hash.split('?')[1]).get('reservationId');
    if (!reservationId) throw new Error('预约提交结果缺少预约 ID');
    const reservationNo = (await app.locator('.booking-submitted__number-value').innerText()).trim();
    const orderCard = app.getByTestId('inspection-order-' + reservationId);
    log('客户提交预约');
    const web = await openAdmin('dev-provider', '/workbench/inspection');
    await web.getByRole('row').filter({ hasText: reservationNo }).getByRole('button', { name: '处理' }).click();
    await web.getByRole('button', { name: /出方案/ }).click();
    await web.waitForTimeout(600);
    await dialog(web).locator('textarea').first().fill('输电线路巡检方案');
    await dialog(web).getByPlaceholder('如：园区周界约 3.2 公里').fill('线路约 12 公里');
    await dialog(web).getByPlaceholder('单价（元）').first().fill('6800');
    if (!(await dialog(web).locator('.el-checkbox-group .is-checked').count())) await dialog(web).locator('.el-checkbox-group .el-checkbox').first().click();
    await dialog(web).getByRole('button', { name: '发布方案' }).click();
    await confirmBox(web);
    await success(web);
    log('服务商发布方案');
    await go(app, '/pages/mine/inspection-orders');
    await orderCard.getByText('确认方案', { exact: true }).click();
    await app.locator('uni-button').filter({ hasText: '我已核对' }).click();
    await app.waitForTimeout(400);
    await app.locator('.plan-confirmation__primary').last().click();
    await app.waitForTimeout(800);
    if (await app.locator('uni-modal').isVisible().catch(() => false)) await modal(app);
    await tap(app, 'plan-pay-down');
    await payHere(app);
    await shot(app, '1-首付');
    log('客户确认方案、付首付 30%');
    await web.getByRole('button', { name: '刷新', exact: true }).click();
    await web.getByRole('button', { name: /安排/ }).click();
    const device = dialog(web).locator('input[placeholder^="设备名称"]');
    if (!(await device.count())) await dialog(web).getByRole('button', { name: '加设备' }).click();
    const member = dialog(web).locator('input[placeholder="姓名或班组"]').first();
    if (!(await member.inputValue())) await member.fill('王机长');
    if (!(await device.first().inputValue())) await device.first().fill('M350 RTK');
    await dialog(web).getByRole('button', { name: '保存' }).click();
    await confirmBox(web);
    await dialog(web).waitFor({ state: 'hidden' });
    const row = web.locator('.el-table__row').filter({ hasText: '第 1 次' });
    for (const name of ['排期', '开始巡检', '现场完成']) {
      await row.getByRole('button', { name, exact: true }).click();
      const note = dialog(web).locator('textarea');
      if (await note.count()) await note.fill(name);
      await dialog(web).getByRole('button', { name: '确认' }).click();
      await confirmBox(web);
      await dialog(web).waitFor({ state: 'hidden' });
    }
    log('服务商安排、排期、开工、现场完成');
    await row.getByRole('button', { name: '上传交付并发布' }).click();
    const names = { zip: '巡检影像.zip', xlsx: '缺陷清单.xlsx', kml: '点位标注.kml', pdf: '巡检报告.pdf' };
    for (const [extension, filename] of Object.entries(names)) {
      await dialog(web).locator(`input[type=file][accept=".${extension}"]`).setInputFiles(sample(filename));
    }
    const numbers = dialog(web).locator('.el-input-number input');
    for (let i = 0; i < await numbers.count(); i += 1) if (!(await numbers.nth(i).inputValue())) await numbers.nth(i).fill('3');
    await dialog(web).getByRole('button', { name: '上传并发布' }).click();
    await confirmBox(web);
    await expect(dialog(web)).toBeHidden({ timeout: 20_000 });
    await expect(row).toContainText('已交付，待客户验收');
    log('服务商上传四类文件并交付');
    await go(app, '/pages/mine/inspection-orders');
    await orderCard.getByTestId('inspection-order-action').click();
    await app.locator('.delivery__accept').click();
    await app.locator('.acceptance-sheet__confirm').click();
    await tap(app, 'delivery-pay');
    await payHere(app);
    await expect(app.locator('body')).toContainText('服务单已完成');
    await shot(app, '1-结清');
    log('客户验收、付清，服务单完成');
  },

  async 2() {
    const token = await login('dev-customer');
    const book = await api('GET', '/api/v1/account/addresses', token).catch(() => ({ items: [] }));
    const app = await openApp('dev-customer');
    await go(app, '/pages/mall/product?productId=c2000000-0000-4000-8000-000000000101&cityCode=370500');
    await app.locator('[data-testid="mall-product-primary"][data-intent="BUY_NOW"]').click();
    await tap(app, 'mall-buy-now');
    if (!(book.items ?? book).length) {
      await api('POST', '/api/v1/account/addresses', token, { recipientName: '王女士', phone: '13800002026', cityCode: CITY, detail: '示例路 88 号', isDefault: true }, { 'Idempotency-Key': crypto.randomUUID() })
        .catch((error) => { throw new Error(`先在 App 里给 dev-customer 加一条收货地址（${error.message}）`); });
      await app.reload();
    }
    await expect(app.getByTestId('checkout-address')).toContainText('王女士');
    await tap(app, 'checkout-agree');
    await tap(app, 'checkout-submit');
    goodsOrderNo = (await app.getByTestId('cashier-order-no').textContent()).trim();
    await payHere(app);
    await tap(app, 'pay-result-view-order');
    await expect(app.getByTestId('goods-order-payment-status')).toContainText('已支付');
    log(`客户下单付款 ${goodsOrderNo}`);
    const web = await openAdmin('dev-merchant', '/workbench/mall-orders');
    await web.locator('.el-table__row', { hasText: goodsOrderNo }).getByRole('button', { name: '发货' }).click();
    await dialog(web).locator('.ship-carrier input').fill('顺丰速运');
    await dialog(web).locator('.ship-tracking input').fill('SF2026112400001');
    await dialog(web).getByRole('button', { name: '确认发货' }).click();
    await confirmBox(web);
    await success(web);
    log('商家在工作台发货');
    await app.reload();
    await tap(app, 'goods-order-action-confirm_receipt');
    await modal(app);
    await expect(app.getByTestId('goods-order-status-title')).toHaveText('已完成');
    await shot(app, '2-已完成');
    log('客户确认收货，订单完成');
    items.app2 = app;
  },

  async 3() {
    if (!items.app2) await items[2]();
    const app = items.app2;
    await tap(app, 'goods-order-aftersale-apply');
    await app.waitForURL(/aftersale-apply/);
    await app.getByTestId('aftersale-apply-description').locator('textarea').fill('未拆封，不想要了');
    await app.waitForTimeout(200);
    await tap(app, 'aftersale-apply-submit');
    await expect(app.getByTestId('aftersale-status-title')).toHaveText('等待商家处理');
    log('客户申请退货退款');
    const web = await openAdmin('dev-merchant', '/workbench/aftersales');
    await web.locator('.el-table__row', { hasText: goodsOrderNo }).getByRole('button').first().click();
    await web.getByRole('button', { name: /^同意/ }).first().click();
    await dialog(web).locator('.el-dialog__footer .el-button--primary').click();
    await confirmBox(web);
    await success(web);
    log('商家同意');
    await tap(app, 'aftersale-refresh');
    await tap(app, 'aftersale-action-submit_return');
    await app.getByTestId('aftersale-carrier-name').locator('input').fill('顺丰速运');
    await app.getByTestId('aftersale-tracking-no').locator('input').fill('2026112400012');
    await app.waitForTimeout(200);
    await tap(app, 'aftersale-waybill-submit');
    await expect(app.getByTestId('aftersale-status-title')).toHaveText('等待商家收货');
    await web.reload();
    await web.getByRole('button', { name: /确认收货并退款/ }).click();
    await dialog(web).getByRole('button', { name: '确认收货并退款' }).click();
    await confirmBox(web);
    await success(web);
    log('商家确认收货并退款');
    await app.waitForTimeout(6000);
    await app.reload();
    await expect(app.getByTestId('aftersale-status-title')).toHaveText('已退款');
    await shot(app, '3-已退款');
    await go(app, '/pages/message/index');
    await expect(app.locator('body')).toContainText('退款已原路退回');
    await shot(app, '3-站内信');
    log('模拟微信自动退款到账，客户收到站内信');
  },

  async 4() {
    const app = await openApp('dev-customer');
    const token = await login('dev-customer');
    const list = await api('GET', `/api/v1/activities?cityCode=${CITY}&size=50`);
    const activity = list.items.find((item) => item.title === '无人机飞行体验课（演示）');
    if (!activity) throw new Error('先跑 node infra/demo/demo.mjs prepare');
    // 同一个人不能重复报同一场：每次彩排加一个新的孩子。
    const child = `演示孩子${Date.now().toString().slice(-4)}`;
    await api('POST', '/api/v1/activity-participants', token, { name: child, birthMonth: '2014-05', guardianConfirmed: true });
    await go(app, `/pages/activity/detail?activityId=${activity.activityId}&cityCode=${CITY}`);
    await app.getByTestId(/^activity-session-[0-9a-f-]{36}$/).last().click();
    await app.locator('[data-testid="activity-detail-primary"]:not([disabled])').click();
    await app.locator(`[data-testid^="activity-enroll-person-"][data-name^="${child}"]`).click();
    await tap(app, 'activity-enroll-agree');
    await shot(app, '4-报名');
    await app.locator('[data-testid="activity-enroll-submit"]').click();
    await payHere(app);
    await tap(app, 'pay-result-view-order');
    const code = (await app.getByTestId('ticket-code').textContent()).trim();
    await shot(app, '4-凭证');
    log(`客户报名付款，凭证 ${code}`);
    const staff = await openApp('dev-organizer');
    await go(staff, '/pages/mine/index');
    await tap(staff, 'mine-link-staff_work_mode');
    // 先选中这张凭证的场次，不然会判“场次不符”。
    await staff.getByTestId('staff-verify-sessions').locator('uni-button').filter({ hasText: '无人机飞行体验课（演示）' }).last().click();
    await staff.waitForTimeout(600);
    if (!(await staff.getByTestId('staff-verify-manual').isVisible())) await tap(staff, 'staff-verify-manual-toggle');
    await staff.getByTestId('staff-verify-code').locator('input').fill(code);
    await staff.waitForTimeout(300);
    await tap(staff, 'staff-verify-submit');
    await expect(staff.locator('body')).toContainText('核销成功');
    await shot(staff, '4-核销');
    log('操作员在工作模式核销（浏览器里输码，真机扫码）');
    await app.reload();
    await expect(app.locator('body')).toContainText(/已核销|已签到|已参加/);
    await shot(app, '4-核销后');
    log('客户凭证状态随之变化');
  },

  async 5() {
    const tag = `演示机构${Date.now().toString().slice(-5)}`;
    const phone = `139${String(crypto.randomInt(1e8)).padStart(8, '0')}`;
    const loginName = `demo${Date.now().toString(36)}`;
    const secret = `Demo${crypto.randomBytes(4).toString('hex')}1`;
    const web = await (await browser.newContext({ viewport: { width: 1440, height: 1200 } })).newPage();
    web.setDefaultTimeout(20_000);
    await web.goto(`${ADMIN}/signup`);
    const field = (label) => web.locator('.el-form-item').filter({ hasText: label }).locator('input, textarea').first();
    await field('机构名称').fill(tag);
    await web.getByPlaceholder('请选择省份 / 城市').click();
    await web.getByRole('menuitem', { name: '山东省' }).click();
    await web.getByRole('menuitem', { name: '东营市' }).click();
    await field('主体名称').fill(`${tag}科技有限公司`);
    await field('统一社会信用代码').fill(credit());
    await field('联系人姓名').fill('李经理');
    await field('联系人手机号').fill(phone);
    await web.getByTestId('send-code').click();
    let code = null;
    for (let i = 0; i < 10 && !code; i += 1) {
      await web.waitForTimeout(700);
      const text = await (await fetch(`http://127.0.0.1:${PORTS.console}/`)).text();
      code = new RegExp(`${phone}</td><td><b class="code">(\\d{6})`).exec(text)?.[1] ?? null;
    }
    if (!code) throw new Error('演示控制台未显示本次申请的短信验证码');
    log('已从演示控制台核对本次申请的验证码');
    await field('短信验证码').fill(code);
    await web.getByLabel('用户名', { exact: true }).fill(loginName);
    await web.locator('input[type=password]').nth(0).fill(secret);
    await web.locator('input[type=password]').nth(1).fill(secret);
    const agree = web.locator('.el-checkbox:not(.is-checked)');
    if (await agree.count()) await agree.first().click();
    const submitting = web.waitForResponse((response) => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/organization-signups');
    await web.getByRole('button', { name: '提交申请' }).click();
    const submitted = await submitting;
    if (submitted.status() !== 201) throw new Error(`入驻提交未成功（HTTP ${submitted.status()}）`);
    await expect(web.getByTestId('signup-login-name')).toHaveText(loginName);
    await shot(web, '5-入驻');
    log('新机构在网页提交入驻');
    const token = await login(loginName, secret);
    const pub = async () => {
      const list = await api('GET', `/api/v1/mall/products?cityCode=${CITY}&keyword=${encodeURIComponent(tag)}&size=20`);
      return list.items.length > 0;
    };
    const idem = () => ({ 'Idempotency-Key': crypto.randomUUID() });
    const W = '/api/v1/provider/catalog';
    let detail = await api('POST', `${W}/products`, token, { category: 'DRONE', name: `${tag}无人机`, saleMode: 'INQUIRY' }, idem());
    const id = detail.product.productId;
    const form = new FormData();
    const picture = path.join(REPO, 'apps/mobile/src/static/images/preview/drone-aerial.jpg');
    form.append('file', new Blob([fs.readFileSync(picture)], { type: 'image/jpeg' }), 'cover.jpg');
    const cover = await (await fetch(`${API}${W}/media`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form })).json();
    detail = await api('POST', `${W}/products/${id}/skus`, token, { name: '标准版' });
    detail = await api('POST', `${W}/products/${id}/offers`, token, { skuId: detail.skus[0].skuId, merchantKind: 'LOCAL_STORE', cityCode: CITY, priceAmountMinor: null, priceUnit: 'ITEM', stockQuantity: null, expressAvailable: true, shippingFeeMinor: 0, pickupPoint: null, deliveryNote: '现货', shippingNote: '包邮', fulfilledFrom: '东营仓' }, idem());
    detail = await api('POST', `${W}/products/${id}/offers/${detail.offers[0].offerId}/status`, token, { action: 'ACTIVATE', version: detail.offers[0].version });
    detail = await api('PUT', `${W}/products/${id}`, token, { category: 'DRONE', name: `${tag}无人机`, summary: '演示用', coverUrl: cover.url, experienceBadge: null, sortOrder: 1, sellingPoints: ['演示'], specs: [{ label: '用途', value: '演示' }], packItems: [{ name: '整机', quantity: 1 }], gallery: [], introBlocks: [{ type: 'TEXT', text: '演示', imageUrl: null }], version: detail.product.version });
    await api('POST', `${W}/products/${id}/status`, token, { action: 'PUBLISH', version: detail.product.version })
      .then(() => { throw new Error('审核前竟然能上架'); }, (error) => { if (!/OPS_MERCHANT_NOT_QUALIFIED/.test(error.message)) throw error; });
    if (await pub()) throw new Error('审核前 App 已经能看到');
    const op = await login('dev-operator', OPERATOR_PASSWORD);
    const seen = await api('GET', `/api/v1/ops/catalog/products/${id}`, op);
    await api('POST', `/api/v1/ops/catalog/products/${id}/status`, op, { action: 'PUBLISH', version: seen.product.version })
      .then(() => { throw new Error('审核前运营端竟然能代上架'); }, (error) => { if (!/OPS_MERCHANT_NOT_QUALIFIED/.test(error.message)) throw error; });
    log('审核前：机构自己和运营端都不能上架，App 看不到');
    const me = await api('GET', '/api/v1/provider/organization', token);
    const pdf = new FormData();
    pdf.append('file', new Blob([fs.readFileSync(sample('巡检报告.pdf'))], { type: 'application/pdf' }), '营业执照.pdf');
    await fetch(`${API}/api/v1/provider/organization/attachments`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: pdf });
    const orgId = me.organization.organizationId;
    const org = await api('GET', `/api/v1/ops/organizations/${orgId}`, op);
    await api('POST', `/api/v1/ops/organizations/${orgId}/qualification/review`, op, { decision: 'APPROVE', version: org.qualification.version });
    const now = await api('GET', `${W}/products/${id}`, token);
    await api('POST', `${W}/products/${id}/status`, token, { action: 'PUBLISH', version: now.product.version });
    if (!(await pub())) throw new Error('审核后 App 看不到');
    const app = await openApp(null);
    await go(app, `/pages/mall/product?productId=${id}&cityCode=${CITY}`);
    await expect(app.locator('body')).toContainText(`${tag}无人机`);
    const loaded = await app.locator(`img[src*="/api/v1/mall/media/products/"]`).first().evaluate((img) => img.complete && img.naturalWidth > 0).catch(() => false);
    if (!loaded) throw new Error('App 里没显示上传的商品图');
    await shot(app, '5-App可见');
    log('运营端审核通过后上架，App 可见，图片显示正常');
    const changed = await api('GET', `/api/v1/ops/organizations/${orgId}`, op);
    await api('PUT', `/api/v1/ops/organizations/${orgId}/qualification`, op, { subjectName: `${tag}改名`, licenseNumber: credit(), businessScope: '演示', qualificationNote: '演示', version: changed.qualification.version });
    if (await pub()) throw new Error('资质改动回到待审核后，App 还能看到');
    log('资质改动回到待审核，App 不再显示');
  },

  async 8() {
    const web = await openAdmin('dev-support', '/');
    await web.getByTestId('kpi-orders').waitFor();
    const read = async () => Number((await web.getByTestId('kpi-orders').innerText()).replace(/[^\d]/g, ' ').trim().split(/\s+/)[0]);
    const before = await read();
    items.app2 = null;
    await items[2]();
    await web.getByRole('button', { name: '刷新', exact: true }).click();
    await expect.poll(read).toBe(before + 1);
    const after = await read();
    await shot(web, '8-经营概览');
    log(`新增一笔订单后，付款订单 ${before} → ${after}`);
  },
};

let failed = 0;
for (const key of ['1', '2', '3', '4', '5', '8']) {
  if (wanted.length && !wanted.includes(key)) continue;
  const started = Date.now();
  console.log(`第 ${key} 条`);
  try { await items[key](); console.log(`  通过（${Math.round((Date.now() - started) / 1000)} 秒）`); } catch (error) {
    failed += 1;
    console.log(`  没过：${error.message.split('\n').slice(0, 6).join(' / ')}`);
    let n = 0;
    for (const context of browser.contexts()) for (const page of context.pages()) { n += 1; await shot(page, `失败-${key}-${n}`).catch(() => undefined); }
  }
  for (const context of browser.contexts()) if (key !== '2') await context.close();
}
await browser.close();
process.exit(failed ? 1 : 0);
