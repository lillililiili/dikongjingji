import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import App from '@/App.vue'
import { useSessionStore } from '@/core/auth/session'
import { tokenStore } from '@/core/auth/token-store'
import { createAdminRouter } from '@/core/navigation/router'
import { ADMIN_MODULES } from '@/modules'

/**
 * 机构工作台的集成测试（S3-2）：挂整个运营端（真实的路由、守卫、布局、登录状态、接口客户端和页面），
 * 后端换成按契约回数据的假 fetch。走一遍商家的日常：没登录先去登录页 → 登录 → 回到商城订单的“待发货” →
 * 发货（带 If-Match 和幂等键）→ 列表刷新；再看菜单只有机构工作台、进不了平台运营的页面、客户账号进不来。
 * 真后端上的同一路径由主工作区的 E2E 跑（见 README）。
 */

interface Call {
  method: string
  url: string
  headers: Record<string, string>
  body: unknown
}

const MERCHANT_ME = {
  displayName: '示例机器人商家操作员',
  organizationName: '示例机器人商家',
  organizationType: 'PROVIDER',
  roles: ['PROVIDER_OPERATOR', 'LIVE_HOST'],
}

function paidOrder(version: number, status = 'PAID') {
  return {
    orderId: 'e1000000-0000-4000-8000-000000000001',
    orderNo: 'MO202611230001',
    source: 'MALL',
    sourceRef: null,
    status,
    merchant: { organizationId: 'c1000000-0000-4000-8000-000000000001', name: '示例机器人商家', kind: 'FACTORY_DIRECT' },
    lines: [{ lineId: 'l-1', productId: 'p-1', offerId: 'f-1', productName: '编程机器人探索套装', skuName: '标准版',
      coverImage: null, unitAmountMinor: 49900, quantity: 1, subtotalMinor: 49900 }],
    delivery: { mode: 'EXPRESS', recipientName: '王女士', phoneMasked: '138****6621', recipientPhone: '13812346621',
      addressLine: '山东省东营市东营区府前大街 1 号', pickupPoint: null, pickupCode: null,
      carrierName: status === 'SHIPPED' ? '顺丰速运' : null, trackingNo: status === 'SHIPPED' ? 'SF1234567890' : null,
      shippedAt: status === 'SHIPPED' ? '2026-11-23T02:00:00Z' : null },
    amounts: { goodsMinor: 49900, shippingMinor: 0, payableMinor: 49900, paidMinor: 49900, refundedMinor: 0,
      currency: 'CNY' },
    buyerNote: null,
    createdAt: '2026-11-23T01:00:00Z',
    payBefore: null,
    paidAt: '2026-11-23T01:05:00Z',
    completedAt: null,
    closedAt: null,
    closeReason: null,
    refunds: [],
    cancellable: status === 'PAID',
    version,
  }
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** 假后端：只认这几条路由，别的一律 404（页面不该调别的接口）。 */
function fakeBackend(me: typeof MERCHANT_ME) {
  const calls: Call[] = []
  let shipped = false
  const handler = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const headers = (init?.headers ?? {}) as Record<string, string>
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : null
    calls.push({ method, url, headers, body })
    const path = url.split('?')[0]
    if (method === 'POST' && path === '/api/v1/auth/login') {
      return json(200, { accessToken: 'jwt-merchant', tokenType: 'Bearer', expiresInSeconds: 900 })
    }
    if (headers.Authorization !== 'Bearer jwt-merchant') {
      return json(401, { code: 'COMMON_UNAUTHORIZED', message: 'login required', traceId: 't-401' })
    }
    if (method === 'GET' && path === '/api/v1/auth/me') return json(200, me)
    if (method === 'GET' && path === '/api/v1/provider/trade/orders') {
      const items = shipped ? [] : [paidOrder(3)]
      return json(200, { page: 1, size: 20, total: items.length, hasMore: false, items })
    }
    if (method === 'POST' && path === '/api/v1/provider/trade/orders/e1000000-0000-4000-8000-000000000001/shipment') {
      if (headers['If-Match'] !== '"3"') {
        return json(412, { code: 'TRADE_VERSION_CONFLICT', message: 'version', traceId: 't-412' })
      }
      shipped = true
      return json(200, paidOrder(4, 'SHIPPED'))
    }
    return json(404, { code: 'COMMON_NOT_FOUND', message: 'not found', traceId: 't-404' })
  })
  return { handler, calls }
}

async function settle(): Promise<void> {
  for (let i = 0; i < 4; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function buttonByText(root: ParentNode, text: string): HTMLButtonElement {
  const button = Array.from(root.querySelectorAll('button')).find((candidate) => candidate.textContent?.trim() === text)
  if (!button) throw new Error(`没有找到按钮“${text}”`)
  return button as HTMLButtonElement
}

async function typeInto(selector: string, value: string): Promise<void> {
  const input = document.querySelector<HTMLInputElement>(`${selector} input`)
  if (!input) throw new Error(`没有找到输入框 ${selector}`)
  input.value = value
  input.dispatchEvent(new Event('input'))
  await settle()
}

describe('机构工作台（整个运营端 + 假后端）', () => {
  let wrapper: VueWrapper | null = null

  beforeEach(() => {
    tokenStore.clear()
    window.history.replaceState({}, '', '/')
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    document.body.innerHTML = ''
    tokenStore.clear()
  })

  async function mountApp(me: typeof MERCHANT_ME) {
    const backend = fakeBackend(me)
    vi.stubGlobal('fetch', backend.handler)
    const pinia = createPinia()
    setActivePinia(pinia)
    const session = useSessionStore(pinia)
    const router = createAdminRouter(ADMIN_MODULES, () => session)
    wrapper = mount(App, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
    return { backend, router }
  }

  async function signIn(router: Awaited<ReturnType<typeof mountApp>>['router'], loginName: string) {
    await router.push('/workbench/mall-orders')
    await settle()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/workbench/mall-orders')
    const name = document.querySelector<HTMLInputElement>('input[autocomplete="username"]')
    const password = document.querySelector<HTMLInputElement>('input[autocomplete="current-password"]')
    name!.value = loginName
    name!.dispatchEvent(new Event('input'))
    password!.value = 'not-a-real-password'
    password!.dispatchEvent(new Event('input'))
    await settle()
    document.querySelector('form')!.dispatchEvent(new Event('submit'))
    await vi.waitFor(() => expect(router.currentRoute.value.path).not.toBe('/login'), { timeout: 3000 })
    await settle()
  }

  it('商家登录后回到待发货，发货带订单版本和幂等键，列表跟着刷新', async () => {
    const { backend, router } = await mountApp(MERCHANT_ME)
    await signIn(router, 'dev-merchant')

    expect(router.currentRoute.value.path).toBe('/workbench/mall-orders')
    // 菜单：只有机构工作台；直播主办多一项“直播间”；没有平台运营的菜单。
    const menu = document.querySelector('.layout__menu')!.textContent ?? ''
    for (const title of ['商城订单', '商城询价', '租赁询价', '租赁订单', '需求报价', '巡检预约', '客服会话', '直播间', '收款账户']) {
      expect(menu).toContain(title)
    }
    expect(menu).not.toContain('城市开通')
    expect(menu).not.toContain('举报与反馈')

    // 默认页签“待发货”：已付款的快递单。
    const listCall = backend.calls.find((call) => call.url.startsWith('/api/v1/provider/trade/orders'))!
    expect(listCall.url).toBe('/api/v1/provider/trade/orders?status=PAID&deliveryMode=EXPRESS&page=1&size=20')
    await vi.waitFor(() => expect(document.body.textContent).toContain('MO202611230001'))

    buttonByText(document.body, '发货').click()
    await settle()
    await typeInto('.ship-carrier', '顺丰速运')
    await typeInto('.ship-tracking', 'SF1234567890')
    buttonByText(document.querySelector('.el-dialog')!, '确认发货').click()
    await settle()
    // 确认框（ElMessageBox）里再点一次“确认发货”。
    await vi.waitFor(() => expect(document.querySelector('.el-message-box')).not.toBeNull())
    buttonByText(document.querySelector('.el-message-box')!, '确认发货').click()

    await vi.waitFor(() => expect(backend.calls.some((call) => call.url.endsWith('/shipment'))).toBe(true))
    const ship = backend.calls.find((call) => call.url.endsWith('/shipment'))!
    expect(ship.method).toBe('POST')
    expect(ship.headers['If-Match']).toBe('"3"')
    expect(ship.headers['Idempotency-Key']).toMatch(/^[0-9a-f-]{36}$/)
    expect(ship.body).toEqual({ carrierName: '顺丰速运', trackingNo: 'SF1234567890' })
    await vi.waitFor(() => expect(document.body.textContent).toContain('没有这类订单'))
  })

  it('机构账号打开平台运营的页面去“没有权限”，不请求运营接口', async () => {
    const { backend, router } = await mountApp(MERCHANT_ME)
    await signIn(router, 'dev-merchant')

    await router.push('/cities')
    await settle()
    expect(router.currentRoute.value.name).toBe('forbidden')
    expect(backend.calls.some((call) => call.url.startsWith('/api/v1/ops/'))).toBe(false)
  })

  it('只有服务商操作员角色（没有直播主办）时没有“直播间”，直接打开也进不去', async () => {
    const { router } = await mountApp({ ...MERCHANT_ME, displayName: '云翼科技操作员', organizationName: '云翼科技',
      roles: ['PROVIDER_OPERATOR'] })
    await signIn(router, 'dev-provider')

    expect(document.querySelector('.layout__menu')!.textContent).not.toContain('直播间')
    await router.push('/workbench/live-rooms')
    await settle()
    expect(router.currentRoute.value.name).toBe('forbidden')
  })
})
