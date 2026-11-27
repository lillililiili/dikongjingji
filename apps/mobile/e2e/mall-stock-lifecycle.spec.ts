import { randomUUID } from 'node:crypto'
import { expect, test, type APIRequestContext, type APIResponse, type Page } from '@playwright/test'
import { openSignedIn, randomPhone, signUpByApi } from './support/account-fixtures'
import { testAdmin } from './support/test-stack'
import { API, CITY, MERCHANT, FAKE_WECHAT, apiLogin, devPassword, fakeWechatPays, fakeWechatRefunds, latestRefundNo, openPaymentNo, psql, type Session } from './support/trade-fixtures'

test.use({ trace: 'off', actionTimeout: 15_000 })
test.describe.configure({ timeout: 150_000 })
const orders = API + '/api/v1/trade/orders'
const catalog = API + '/api/v1/provider/catalog'
const price = 999

async function json(response: APIResponse) {
  expect(response.ok(), `${response.status()} ${new URL(response.url()).pathname} ${await response.text()}`).toBe(true)
  return response.json()
}

// Each run creates its own SKUs/offers, preserving the reference catalog and other suites' inventory.
async function sell(request: APIRequestContext, merchant: Session, stockQuantity: number | null) {
  const name = '库存验收机器人 ' + randomUUID().slice(0, 8)
  const auth = merchant.headers
  const idempotent = () => ({ ...auth, 'Idempotency-Key': randomUUID() })
  const draft = await json(await request.post(catalog + '/products', {
    headers: idempotent(), data: { category: 'ROBOT', name, saleMode: 'BUY' },
  }))
  const id = draft.product.productId
  const product = catalog + '/products/' + id
  const media = await json(await request.post(catalog + '/media', {
    headers: auth, multipart: { file: { name: 'robot.png', mimeType: 'image/png', buffer: Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64') } },
  }))
  const sku = await json(await request.post(product + '/skus', { headers: auth, data: { name: '标准版' } }))
  const offered = await json(await request.post(product + '/offers', { headers: idempotent(), data: {
    skuId: sku.skus[0].skuId, merchantKind: 'LOCAL_STORE', cityCode: CITY, priceAmountMinor: price,
    priceUnit: 'ITEM', stockQuantity, expressAvailable: true, shippingFeeMinor: 0, pickupPoint: '东营验收点',
  } }))
  const { offerId, version } = offered.offers[0]
  const active = await json(await request.post(product + '/offers/' + offerId + '/status', {
    headers: auth, data: { action: 'ACTIVATE', version },
  }))
  const edited = await json(await request.put(product, { headers: auth, data: {
    category: 'ROBOT', name, summary: '隔离验收专用', coverUrl: media.url, sortOrder: 999, sellingPoints: [],
    specs: [{ label: '型号', value: '标准版' }], packItems: [{ name: '机器人', quantity: 1 }], gallery: [],
    introBlocks: [{ type: 'TEXT', text: '库存验收' }], version: active.product.version,
  } }))
  await json(await request.post(product + '/status', { headers: auth, data: { action: 'PUBLISH', version: edited.product.version } }))
  return { productId: id as string, offerId: offerId as string, name }
}

function submit(request: APIRequestContext, buyer: Session, offerId: string, key = randomUUID()) {
  return request.post(orders, { headers: { ...buyer.headers, 'Idempotency-Key': key }, data: {
    cityCode: CITY, offerId, quantity: 1, deliveryMode: 'PICKUP', expectedPayableMinor: price, agreementAccepted: true,
  } })
}

function cancel(request: APIRequestContext, buyer: Session, order: { orderId: string; version: number }, key = randomUUID()) {
  return request.post(orders + '/' + order.orderId + '/cancel', {
    headers: { ...buyer.headers, 'Idempotency-Key': key, 'If-Match': '"' + order.version + '"' },
  })
}

async function available(request: APIRequestContext, item: Awaited<ReturnType<typeof sell>>) {
  const detail = await json(await request.get(API + '/api/v1/mall/products/' + item.productId + '?cityCode=' + CITY))
  return detail.offers.find((o: { offerId: string }) => o.offerId === item.offerId).stockQuantity
}

async function adminSignIn(page: Page, productId: string, operator = false) {
  await page.setViewportSize({ width: 1366, height: 900 })
  await page.goto(testAdmin + (operator ? '/catalog/products/' : '/workbench/products/') + productId)
  await page.locator('input[autocomplete="username"]').fill(operator ? 'dev-operator' : MERCHANT)
  await page.locator('input[autocomplete="current-password"]').fill(operator ? process.env.DEV_OPERATOR_PASSWORD! : devPassword())
  await page.locator('form').getByRole('button', { name: '登录' }).click()
  await page.getByRole('tab', { name: /在售供给/ }).click()
}

test('库存最后一件：不同买家并发、重复提交、取消归还、再买成交及重复回执', async ({ page, context, request }) => {
  const merchant = await apiLogin(request, MERCHANT)
  expect(merchant).toBeTruthy()
  const item = await sell(request, merchant!, 1)
  const a = await signUpByApi(request, randomPhone()), b = await signUpByApi(request, randomPhone())
  const mobile = await context.newPage()
  const productUrl = '/pages/mall/product?productId=' + item.productId + '&cityCode=' + CITY
  await openSignedIn(mobile, a.token, productUrl)
  await mobile.getByTestId('mall-product-cart').click()
  await expect(mobile.getByTestId('mall-buy-merchant')).toContainText('可售 1 件')
  await adminSignIn(page, item.productId)
  await expect(page.getByRole('cell', { name: '1 件', exact: true })).toBeVisible()
  const keys = [randomUUID(), randomUUID()]
  const responses = await Promise.all([submit(request, a, item.offerId, keys[0]), submit(request, b, item.offerId, keys[1])])
  expect(responses.map(r => r.status()).sort()).toEqual([201, 409])
  const won = responses[0].status() === 201 ? 0 : 1
  const winner = won === 0 ? a : b, other = won === 0 ? b : a
  const ordered = await json(responses[won])
  expect((await responses[1 - won].json()).code).toBe('MALL_CHECKOUT_ITEM_UNAVAILABLE')
  expect((await json(await submit(request, winner, item.offerId, keys[won]))).orderId).toBe(ordered.orderId)
  expect(await available(request, item)).toBe(0)
  await page.reload()
  await page.getByRole('tab', { name: /在售供给/ }).click()
  await expect(page.getByTestId('offer-stock-' + item.offerId)).toContainText('0 件')
  await openSignedIn(mobile, winner.token, productUrl)
  await expect(mobile.getByTestId('mall-offer-stock-' + item.offerId)).toContainText('可售 0 件')
  await openSignedIn(mobile, winner.token, '/pages/mine/goods-order?orderId=' + ordered.orderId)
  await mobile.getByTestId('goods-order-action-cancel').click()
  await mobile.locator('.uni-modal__btn_primary').click()
  await expect.poll(() => available(request, item)).toBe(1)
  const again = await json(await submit(request, other, item.offerId))
  await json(await request.post(orders + '/' + again.orderId + '/payments', {
    headers: { ...other.headers, 'Idempotency-Key': randomUUID() }, data: { channel: 'WECHAT_APP' },
  }))
  const payNo = openPaymentNo(again.orderId)
  await fakeWechatPays(request, payNo)
  expect((await request.post(FAKE_WECHAT + '/_control/renotify/' + payNo)).ok()).toBe(true)
  expect((await json(await request.get(orders + '/' + again.orderId, { headers: other.headers }))).status).toBe('PAID')
  expect(await available(request, item)).toBe(0)
  expect(psql("SELECT status FROM mall_stock_hold WHERE offer_id='" + item.offerId + "' ORDER BY status")).toBe('RELEASED\nSOLD')
})

test('超时归还可售数，另一个买家成交后迟到付款只退原单', async ({ request }) => {
  const merchant = await apiLogin(request, MERCHANT)
  expect(merchant).toBeTruthy()
  const item = await sell(request, merchant!, 1)
  const a = await signUpByApi(request, randomPhone()), b = await signUpByApi(request, randomPhone())
  const first = await json(await submit(request, a, item.offerId))
  await json(await request.post(orders + '/' + first.orderId + '/payments', {
    headers: { ...a.headers, 'Idempotency-Key': randomUUID() }, data: { channel: 'WECHAT_APP' },
  }))
  const lateNo = openPaymentNo(first.orderId)
  // Move only this order's clock so the normal scheduled channel-close and order-timeout jobs run.
  psql("UPDATE trade_order SET created_at=now()-interval '35 minutes', pay_before=now()-interval '2 minutes' WHERE id='" + first.orderId + "'")
  await expect.poll(async () => (await json(await request.get(orders + '/' + first.orderId, { headers: a.headers }))).status,
    { timeout: 100_000, intervals: [500, 1000, 2000] }).toBe('CLOSED')
  expect(await available(request, item)).toBe(1)
  const sold = await json(await submit(request, b, item.offerId))
  await json(await request.post(orders + '/' + sold.orderId + '/payments', {
    headers: { ...b.headers, 'Idempotency-Key': randomUUID() }, data: { channel: 'WECHAT_APP' },
  }))
  await fakeWechatPays(request, openPaymentNo(sold.orderId))
  expect((await request.post(FAKE_WECHAT + '/_control/pay/' + lateNo + '?notify=1')).status()).toBe(409)
  const late = await json(await request.post(FAKE_WECHAT + '/_control/late-pay/' + lateNo + '?notify=1'))
  expect(late.notification.status).toBe(204)
  expect((await json(await request.post(FAKE_WECHAT + '/_control/renotify/' + lateNo))).status).toBe(204)
  await expect.poll(() => latestRefundNo(first.orderId)).not.toBe('')
  await fakeWechatRefunds(request, latestRefundNo(first.orderId))
  await expect.poll(async () => (await json(await request.get(orders + '/' + first.orderId, { headers: a.headers }))).status).toBe('REFUNDED')
  expect((await json(await request.get(orders + '/' + sold.orderId, { headers: b.headers }))).status).toBe('PAID')
  expect(psql("SELECT count(*) FROM trade_refund WHERE order_id='" + first.orderId + "'")).toBe('1')
  expect(await available(request, item)).toBe(0)
})

test('同一购物车多行库存部分不足整单回滚，不限库存不产生预占', async ({ request }) => {
  const merchant = await apiLogin(request, MERCHANT)
  expect(merchant).toBeTruthy()
  const finite = await sell(request, merchant!, 1), scarce = await sell(request, merchant!, 1), unlimited = await sell(request, merchant!, null)
  const buyer = await signUpByApi(request, randomPhone()), rival = await signUpByApi(request, randomPhone())
  const cart = API + '/api/v1/mall/cart/items'
  const ids: string[] = []
  for (const item of [finite, unlimited, scarce]) {
    const added = await json(await request.post(cart, { headers: buyer.headers, data: { offerId: item.offerId, quantity: 1, cityCode: CITY } }))
    ids.push(added.cartItemId)
  }
  const attempt = () => request.post(orders, { headers: { ...buyer.headers, 'Idempotency-Key': randomUUID() }, data: {
    cityCode: CITY, cartItemIds: ids, deliveryMode: 'PICKUP', expectedPayableMinor: price * 3, agreementAccepted: true,
  } })
  const held = await json(await submit(request, rival, scarce.offerId))
  const failed = await attempt()
  expect(failed.status()).toBe(409)
  expect((await failed.json()).code).toBe('MALL_CHECKOUT_ITEM_UNAVAILABLE')
  expect(await available(request, finite)).toBe(1)
  expect(await available(request, unlimited)).toBeNull()
  expect(psql("SELECT count(*) FROM mall_stock_hold WHERE offer_id IN ('" + finite.offerId + "','" + unlimited.offerId + "')")).toBe('0')
  const key = randomUUID()
  await json(await cancel(request, rival, held, key))
  await json(await cancel(request, rival, held, key))
  const placed = await json(await attempt())
  expect(psql("SELECT count(*) FROM mall_stock_hold WHERE order_id='" + placed.orderId + "' AND status='HELD'")).toBe('2')
  expect(await available(request, finite)).toBe(0)
  expect(await available(request, scarce)).toBe(0)
  expect(await available(request, unlimited)).toBeNull()
  await json(await cancel(request, buyer, placed))
  expect(await available(request, finite)).toBe(1)
  expect(await available(request, scarce)).toBe(1)
})

test('App 立即购买确认期间库存变化，返回对应商品后按当前可售量展示', async ({ page, request }) => {
  const merchant = await apiLogin(request, MERCHANT)
  expect(merchant).toBeTruthy()
  const item = await sell(request, merchant!, 1)
  const buyer = await signUpByApi(request, randomPhone()), rival = await signUpByApi(request, randomPhone())
  await openSignedIn(page, buyer.token, '/pages/mall/product?productId=' + item.productId + '&cityCode=' + CITY)
  await page.getByTestId('mall-product-primary').click()
  await page.getByTestId('mall-buy-now').click()
  await page.getByTestId('checkout-mode-pickup').click()
  await page.getByTestId('checkout-agree').click()
  const won = await json(await submit(request, rival, item.offerId))
  const refused = page.waitForResponse(r => r.url().endsWith('/api/v1/trade/orders') && r.request().method() === 'POST')
  await page.getByTestId('checkout-submit').click()
  expect((await refused).status()).toBe(409)
  await expect(page.getByTestId('checkout-stale')).toBeVisible()
  await page.getByTestId('checkout-back-to-product').click()
  await expect(page.getByTestId('mall-product-name')).toHaveText(item.name)
  await expect(page.getByTestId('mall-offer-stock-' + item.offerId)).toContainText('可售 0 件')
  await json(await cancel(request, rival, won))
})

test('运营库存编辑遇到并发预占需重读；切换不限及有限库存后只释放原预占数量', async ({ page, request }) => {
  const merchant = await apiLogin(request, MERCHANT)
  expect(merchant).toBeTruthy()
  const item = await sell(request, merchant!, 2)
  const buyer = await signUpByApi(request, randomPhone())
  await adminSignIn(page, item.productId, true)
  const edit = () => page.getByRole('row', { name: /标准版/ }).getByRole('button', { name: '编辑' }).click()
  const form = page.getByRole('dialog', { name: '修改在售供给' })
  const stock = form.getByPlaceholder('空着表示不限')
  async function save(value: string) {
    await stock.fill(value)
    await stock.press('Tab')
    await form.getByRole('button', { name: '保存', exact: true }).click()
    const pending = page.waitForResponse(r => r.url().endsWith('/offers/' + item.offerId) && r.request().method() === 'PUT')
    await page.locator('.el-message-box').getByRole('button', { name: '保存', exact: true }).click()
    return (await pending).status()
  }
  await edit()
  await expect(stock).toHaveValue('2')
  const first = await json(await submit(request, buyer, item.offerId))
  expect(await save('9')).toBe(409)
  await form.getByTestId('offer-reload').click()
  await expect(stock).toHaveValue('1')
  expect(await save('')).toBe(200)
  await json(await cancel(request, buyer, first))
  expect(await available(request, item)).toBeNull()
  await edit()
  expect(await save('2')).toBe(200)
  const second = await json(await submit(request, buyer, item.offerId))
  await page.reload()
  await page.getByRole('tab', { name: /在售供给/ }).click()
  await edit()
  expect(await save('')).toBe(200)
  await edit()
  expect(await save('5')).toBe(200)
  await json(await cancel(request, buyer, second))
  expect(await available(request, item)).toBe(6)
})

test('App 购物车结算时余量下降，返回调整数量后正常下单', async ({ page, request }) => {
  const merchant = await apiLogin(request, MERCHANT)
  expect(merchant).toBeTruthy()
  const item = await sell(request, merchant!, 2)
  const buyer = await signUpByApi(request, randomPhone()), rival = await signUpByApi(request, randomPhone())
  const line = await json(await request.post(API + '/api/v1/mall/cart/items', { headers: buyer.headers,
    data: { offerId: item.offerId, quantity: 2, cityCode: CITY } }))
  await openSignedIn(page, buyer.token, '/pages/cart/checkout?items=' + line.cartItemId + '&cityCode=' + CITY)
  await page.getByTestId('checkout-mode-pickup').click()
  await page.getByTestId('checkout-agree').click()
  const held = await json(await submit(request, rival, item.offerId))
  await page.getByTestId('checkout-submit').click()
  await page.getByTestId('checkout-back-to-cart').click()
  await expect(page.getByTestId('cart-stock-' + line.cartItemId)).toContainText('可售 1 件')
  await page.getByTestId('cart-adjust-stock-' + line.cartItemId).click()
  await expect(page.getByTestId('cart-qty-' + line.cartItemId)).toHaveText('1')
  const merchantChoice = page.getByRole('radio', { name: /结算.*的商品/ })
  if (await merchantChoice.getAttribute('aria-checked') !== 'true') await merchantChoice.click()
  const lineChoice = page.getByTestId('cart-tick-' + line.cartItemId)
  if (await lineChoice.getAttribute('aria-checked') !== 'true') await lineChoice.click()
  await page.getByTestId('cart-checkout').click()
  await page.getByTestId('checkout-mode-pickup').click()
  await page.getByTestId('checkout-agree').click()
  const order = page.waitForResponse(r => r.url().endsWith('/api/v1/trade/orders') && r.request().method() === 'POST')
  await page.getByTestId('checkout-submit').click()
  const created = await order
  expect(created.status()).toBe(201)
  await expect(page.getByTestId('cashier-amount')).toContainText('9.99')
  expect(await available(request, item)).toBe(0)
  await json(await cancel(request, buyer, await created.json()))
  await json(await cancel(request, rival, held))
})
