import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, RouterView } from 'vue-router'

import { recordingClient } from '@/core/http/recording-client'
import { buildMenu } from '@/core/navigation/module'
import { ADMIN_MODULES } from '@/modules'

import { createLookupApi, label, STATUS_LABELS, type LookupApi, type OrderDetail, type UserDetail } from './lookup-api'
import { lookupModule } from './lookup-module'

const ORDER: OrderDetail = {
  id: 'o-1',
  orderNo: 'MO2026112300000001',
  source: 'MALL',
  status: 'SHIPPED',
  cityCode: '370500',
  merchantName: '示例机器人商家',
  buyer: { userId: 'u-1', displayName: '王女士（示例）', maskedPhone: '138****2026' },
  payerName: null,
  title: '编程机器人探索套装',
  itemCount: 1,
  payableMinor: 99800,
  paidMinor: 99800,
  refundedMinor: 0,
  createdAt: '2026-11-23T02:00:00Z',
  paidAt: '2026-11-23T02:01:00Z',
  sourceRef: null,
  deliveryMode: 'EXPRESS',
  goodsMinor: 99800,
  shippingMinor: 0,
  buyerNote: null,
  payBefore: '2026-11-23T02:15:00Z',
  shippedAt: '2026-11-23T05:00:00Z',
  completedAt: null,
  closedAt: null,
  lines: [{ productName: '编程机器人探索套装', skuName: '标准版', unitAmountMinor: 99800, quantity: 1,
    subtotalMinor: 99800 }],
  delivery: { mode: 'EXPRESS', recipientMasked: '王*', phoneMasked: '139****1234', cityName: '东营市',
    pickupPoint: null, carrierName: '顺丰速运', trackingNo: 'SF1234567890' },
  payments: [{ outTradeNo: 'P2026112300000001', transactionId: '4200001234', amountMinor: 99800,
    status: 'SUCCEEDED', createdAt: '2026-11-23T02:00:30Z', succeededAt: '2026-11-23T02:01:00Z', closedAt: null }],
  refunds: [],
  aftersales: [],
  history: [
    { fromStatus: null, toStatus: 'PENDING_PAYMENT', cause: 'SUBMIT', occurredAt: '2026-11-23T02:00:00Z' },
    { fromStatus: 'PENDING_PAYMENT', toStatus: 'PAID', cause: 'PAYMENT_NOTIFIED', occurredAt: '2026-11-23T02:01:00Z' },
  ],
}

const USER: UserDetail = {
  id: 'u-1',
  displayName: '王女士（示例）',
  loginName: 'dev-customer',
  maskedPhone: '138****2026',
  status: 'CLOSING',
  registeredVia: 'PASSWORD',
  roles: ['CUSTOMER'],
  createdAt: '2026-11-01T00:00:00Z',
  closingDueAt: '2026-12-08T00:00:00Z',
  closedAt: null,
  organizations: [{ id: 'org-1', name: '东营示例物流公司', type: 'CUSTOMER' }],
  enterprises: [],
  activeDeviceCount: 1,
  lastActiveAt: '2026-11-23T01:00:00Z',
  orderCount: 3,
}

function fakeApi(): LookupApi {
  return {
    orders: async () => ({ page: 1, size: 20, total: 0, hasMore: false, items: [] }),
    order: async () => ORDER,
    users: async () => ({ page: 1, size: 20, total: 0, hasMore: false, items: [] }),
    user: async () => USER,
  }
}

async function mountAt(path: string, api = fakeApi()) {
  const router = createRouter({ history: createMemoryHistory(), routes: [...lookupModule.routes] })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(RouterView, {
    attrs: { api },
    global: { plugins: [ElementPlus, router] },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('订单查询、用户查询', () => {
  it('同页从买家订单切换到全部订单或待处理筛选时，重新按地址查询', async () => {
    const api = fakeApi()
    const orders = vi.spyOn(api, 'orders')
    const { wrapper, router } = await mountAt('/lookup/orders?buyerId=u-1&buyerName=客户甲', api)
    expect(orders).toHaveBeenLastCalledWith(expect.objectContaining({ buyerId: 'u-1' }), 1, 20)
    expect(wrapper.text()).toContain('客户甲')
      await wrapper.get('input[data-testid="order-lookup-q"]').setValue('上一笔咨询')
    await router.push('/lookup/orders?status=REFUND_FAILED')
    await flushPromises()
      expect(orders).toHaveBeenLastCalledWith({ q: '', buyerId: '', source: '', status: 'REFUND_FAILED', dates: null }, 1, 20)
    expect(wrapper.text()).not.toContain('客户甲')
    wrapper.unmount()
  })
  it('平台运营和平台客服都看得到两个菜单，机构操作员看不到', () => {
    const titles = (roles: string[]) => buildMenu(ADMIN_MODULES, roles).flatMap((group) => group.items)
      .map((item) => item.title)
    expect(titles(['PLATFORM_SUPPORT']).slice(0, 2)).toEqual(['订单查询', '用户查询'])
    expect(titles(['PLATFORM_OPERATOR'])).toEqual(expect.arrayContaining(['订单查询', '用户查询']))
    expect(titles(['PROVIDER_OPERATOR'])).not.toContain('订单查询')
    expect(titles(['PROVIDER_OPERATOR'])).not.toContain('用户查询')
  })

  it('查询参数按契约发出，空的不带', async () => {
    const { http, requests } = recordingClient({ page: 1, size: 20, total: 0, hasMore: false, items: [] })
    const api = createLookupApi(http)

    await api.orders({ q: ' 13800002026 ', buyerId: '', source: 'MALL', status: '', dates: ['2026-11-01', '2026-11-23'] },
      2, 10)
    await api.orders({ q: '', buyerId: 'u-1', source: '', status: '', dates: null }, 1, 20)
    await api.users({ q: '王' }, 1, 20)
    await api.user('u/1')

    expect(requests.map((request) => request.url)).toEqual([
      '/api/v1/ops/orders?q=13800002026&source=MALL&from=2026-11-01&to=2026-11-23&page=2&size=10',
      '/api/v1/ops/orders?buyerId=u-1&page=1&size=20',
      `/api/v1/ops/users?q=${encodeURIComponent('王')}&page=1&size=20`,
      '/api/v1/ops/users/u%2F1',
    ])
  })

  it('订单详情显示打码后的买家和收货人、微信单号和状态变化', async () => {
    const { wrapper } = await mountAt('/lookup/orders/o-1')
    const text = wrapper.text()

    expect(text).toContain('订单 MO2026112300000001')
    expect(text).toContain('138****2026')
    expect(text).toContain('王*')
    expect(text).toContain('139****1234')
    expect(text).toContain('SF1234567890')
    expect(text).toContain('4200001234')
    expect(text).toContain('付款成功（微信通知） → 已付款')
    expect(text).toContain('详细地址不在这里显示')
  })

  it('用户详情能跳到这个人的订单', async () => {
    const { wrapper, router } = await mountAt('/lookup/users/u-1')

    expect(wrapper.text()).toContain('注销冷静期')
    expect(wrapper.text()).toContain('东营示例物流公司')
    await wrapper.get('[data-testid="user-lookup-orders"]').trigger('click')
    // 目标页是按需加载的，等路由真的切过去。
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('ops-orders'))
    expect(router.currentRoute.value.query).toEqual({ buyerId: 'u-1', buyerName: '王女士（示例）' })
  })

  it('不认识的编码原样显示', () => {
    expect(label(STATUS_LABELS, 'PAID')).toBe('已付款')
    expect(label(STATUS_LABELS, 'SOMETHING_NEW')).toBe('SOMETHING_NEW')
    expect(label(STATUS_LABELS, null)).toBe('—')
  })
})
