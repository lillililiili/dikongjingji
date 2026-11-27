import { execFileSync } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { expect, type APIRequestContext } from '@playwright/test'
import { testApi, testContainer, testDatabase, testPayment } from './test-stack'

export const API = testApi
export const FAKE_WECHAT = testPayment
export const CITY = '370500'
export const BUYER = 'dev-customer'
export const MERCHANT = 'dev-merchant'

// The dev seed derives offer ids from the product and merchant numbers.
export function offerId(product: string, merchant: string): string {
  const hex = createHash('md5').update(`dev-mall-offer:${product}:${merchant}`).digest('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export function devPassword(): string {
  const seed = readFileSync(resolve(
    __dirname,
    '../../../../backend/bootstrap/src/main/resources/db/development/identity/R__dev_identity_accounts.sql',
  ), 'utf8')
  return /crypt\('([^']+)'/.exec(seed)?.[1] ?? ''
}

export interface Session {
  token: string
  headers: { Authorization: string }
}

export async function apiLogin(request: APIRequestContext, loginName: string): Promise<Session | null> {
  const login = await request.post(`${API}/api/v1/auth/login`, {
    data: { loginName, password: devPassword() },
  })
  if (!login.ok()) return null
  const { accessToken } = await login.json()
  return { token: accessToken, headers: { Authorization: `Bearer ${accessToken}` } }
}

export function psql(sql: string): string {
  return execFileSync('docker', [
    'exec', '-i', testContainer, 'sh', '-c',
    'exec psql -X -U "$POSTGRES_USER" -d "$1" -At -q -v ON_ERROR_STOP=1', 'e2e', testDatabase,
  ], { input: sql, encoding: 'utf8' }).trim()
}

export function databaseAvailable(): boolean {
  try {
    return psql('SELECT 1;') === '1'
  } catch {
    return false
  }
}

/**
 * Removes every order of the test buyer and what hangs off it. Only this account's orders: the
 * tests create them and nothing else uses the development buyer's orders.
 */
export function purgeBuyerOrders(): void {
  psql(`
    BEGIN;
    CREATE TEMP TABLE e2e_orders ON COMMIT DROP AS
      SELECT id FROM trade_order WHERE buyer_id = (SELECT id FROM identity_user WHERE login_name = '${BUYER}') FOR UPDATE;
    -- This suite deletes its own test sales. Undo only these orders' outstanding stock effects.
    DO $$ BEGIN
      IF to_regclass('mall_stock_hold') IS NOT NULL THEN
        UPDATE mall_offer o SET stock_quantity = o.stock_quantity + restored.quantity, version = version + 1, updated_at = now()
        FROM (SELECT offer_id, sum(quantity)::integer AS quantity FROM mall_stock_hold
              WHERE order_id IN (SELECT id FROM e2e_orders) AND status IN ('HELD', 'SOLD') GROUP BY offer_id) restored
        WHERE o.id = restored.offer_id AND o.stock_quantity IS NOT NULL;
        DELETE FROM mall_stock_hold WHERE order_id IN (SELECT id FROM e2e_orders);
        DELETE FROM mall_stock_order WHERE order_id IN (SELECT id FROM e2e_orders);
      END IF;
    END $$;
    -- Goods after-sales (S4-2) hang off the orders and their refunds; the pictures the buyer uploaded go too.
    DELETE FROM trade_aftersale_event WHERE aftersale_id IN (SELECT id FROM trade_aftersale WHERE order_id IN (SELECT id FROM e2e_orders));
    DELETE FROM trade_aftersale_image
      WHERE aftersale_id IN (SELECT id FROM trade_aftersale WHERE order_id IN (SELECT id FROM e2e_orders))
         OR uploaded_by = (SELECT id FROM identity_user WHERE login_name = '${BUYER}');
    DELETE FROM trade_aftersale WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_channel_notification
      WHERE out_trade_no IN (SELECT out_trade_no FROM trade_payment WHERE order_id IN (SELECT id FROM e2e_orders))
         OR out_refund_no IN (SELECT out_refund_no FROM trade_refund WHERE order_id IN (SELECT id FROM e2e_orders));
    DELETE FROM trade_state_transition WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_refund WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_payment WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order_delivery WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order_line WHERE order_id IN (SELECT id FROM e2e_orders);
    DELETE FROM trade_order WHERE id IN (SELECT id FROM e2e_orders);
    COMMIT;
  `)
}

function assertUuid(value: string): string {
  if (!/^[0-9a-f-]{36}$/.test(value)) throw new Error(`not an id: ${value}`)
  return value
}

// The open payment of an order (what WeChat calls out_trade_no).
export function openPaymentNo(orderId: string): string {
  return psql(`SELECT out_trade_no FROM trade_payment WHERE order_id = '${assertUuid(orderId)}'
    AND status IN ('CREATED', 'PREPAID') ORDER BY created_at DESC LIMIT 1;`)
}

export function latestRefundNo(orderId: string): string {
  return psql(`SELECT out_refund_no FROM trade_refund WHERE order_id = '${assertUuid(orderId)}'
    ORDER BY created_at DESC LIMIT 1;`)
}

// The fake WeChat pays the order on the buyer's behalf and sends the signed notification.
export async function fakeWechatPays(request: APIRequestContext, outTradeNo: string): Promise<void> {
  expect(outTradeNo).not.toBe('')
  const paid = await request.post(`${FAKE_WECHAT}/_control/pay/${outTradeNo}?notify=1`)
  expect(paid.ok()).toBe(true)
}

export async function fakeWechatRefunds(
  request: APIRequestContext, outRefundNo: string, status: 'SUCCESS' | 'ABNORMAL' = 'SUCCESS',
): Promise<void> {
  expect(outRefundNo).not.toBe('')
  const refunded = await request.post(`${FAKE_WECHAT}/_control/refund/${outRefundNo}?status=${status}&notify=1`)
  expect(refunded.ok()).toBe(true)
}

export async function fakeWechatAvailable(request: APIRequestContext): Promise<boolean> {
  try {
    return (await request.get(`${FAKE_WECHAT}/_control/orders`)).ok()
  } catch {
    return false
  }
}

export interface OrderSnapshot {
  orderId: string
  orderNo: string
  status: string
  // BUYER_CANCEL or TIMEOUT on a closed order (contract E08).
  closeReason: string | null
  version: number
  delivery: { shippedAt: string | null; pickupCode: string | null }
}

export async function submitOrderByApi(
  request: APIRequestContext,
  session: Session,
  input: { offer: string; mode: 'EXPRESS' | 'PICKUP'; payableMinor: number; addressId?: string },
): Promise<OrderSnapshot> {
  const response = await request.post(`${API}/api/v1/trade/orders`, {
    headers: { ...session.headers, 'Idempotency-Key': randomUUID() },
    data: {
      cityCode: CITY,
      offerId: input.offer,
      quantity: 1,
      deliveryMode: input.mode,
      ...(input.addressId ? { addressId: input.addressId } : {}),
      expectedPayableMinor: input.payableMinor,
      agreementAccepted: true,
    },
  })
  expect(response.status()).toBe(201)
  return response.json()
}

export async function readOrderByApi(request: APIRequestContext, session: Session, orderId: string): Promise<OrderSnapshot> {
  const response = await request.get(`${API}/api/v1/trade/orders/${orderId}`, { headers: session.headers })
  expect(response.status()).toBe(200)
  return response.json()
}

// The buyer starts a payment (what the cashier's button does) and the fake WeChat pays it.
export async function payByApi(request: APIRequestContext, session: Session, orderId: string): Promise<void> {
  const launched = await request.post(`${API}/api/v1/trade/orders/${orderId}/payments`, {
    headers: { ...session.headers, 'Idempotency-Key': randomUUID() },
    data: { channel: 'WECHAT_APP' },
  })
  expect(launched.status()).toBe(200)
  await fakeWechatPays(request, openPaymentNo(orderId))
  await expect.poll(async () => (await readOrderByApi(request, session, orderId)).status).toBe('PAID')
}

// The merchant ships through its authorised interface (there is no merchant page).
export async function shipByApi(
  request: APIRequestContext,
  merchant: Session,
  order: OrderSnapshot,
  trackingNo: string,
): Promise<void> {
  const shipped = await request.post(`${API}/api/v1/provider/trade/orders/${order.orderId}/shipment`, {
    headers: { ...merchant.headers, 'Idempotency-Key': randomUUID(), 'If-Match': `"${order.version}"` },
    data: { carrierName: '示例快递', trackingNo },
  })
  expect(shipped.status()).toBe(200)
}

export async function addAddressByApi(request: APIRequestContext, session: Session): Promise<string> {
  const created = await request.post(`${API}/api/v1/account/addresses`, {
    headers: session.headers,
    data: { recipientName: '王女士', phone: '13800002026', cityCode: CITY, detail: '东营区 示例路 88 号', isDefault: true },
  })
  expect(created.status()).toBe(201)
  return (await created.json()).addressId
}

export async function clearAddresses(request: APIRequestContext, session: Session): Promise<void> {
  const addresses = await (await request.get(`${API}/api/v1/account/addresses`, { headers: session.headers })).json()
  for (const address of addresses.items) {
    await request.delete(`${API}/api/v1/account/addresses/${address.addressId}`, { headers: session.headers })
  }
}

// "11/28 10:24" in Beijing time, as the pages write it.
export function beijingShortTime(iso: string): string {
  const shifted = new Date(new Date(iso).getTime() + 8 * 60 * 60 * 1000)
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${pad(shifted.getUTCMonth() + 1)}/${pad(shifted.getUTCDate())} ${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}`
}
