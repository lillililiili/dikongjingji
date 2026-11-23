import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'

import {
  beijingToday,
  createReconciliationApi,
  csvFileName,
  defaultReconciliationDate,
  isSelectableDate,
  type Reconciliation,
  type ReconciliationApi,
} from './reconciliation-api'
import ReconciliationPage from './ReconciliationPage.vue'

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

const RESULT: Reconciliation = {
  date: '2026-11-22',
  importedAt: '2026-11-23T02:05:00Z',
  matchedCount: 12,
  differences: [{
    kind: 'AMOUNT_MISMATCH', subMchid: '1900000101', outTradeNo: 'MO20261122000001', outRefundNo: null,
    platformAmountMinor: 129900, channelAmountMinor: 129800,
  }],
}

describe('对账日期', () => {
  it('按北京时间算今天：UTC 16:00 以后已经是北京的第二天', () => {
    expect(beijingToday(Date.parse('2026-11-22T15:59:00Z'))).toBe('2026-11-22')
    expect(beijingToday(Date.parse('2026-11-22T16:00:00Z'))).toBe('2026-11-23')
    expect(defaultReconciliationDate(Date.parse('2026-11-22T16:30:00Z'))).toBe('2026-11-22')
  })

  it('只能选今天以前', () => {
    const now = Date.parse('2026-11-23T03:00:00Z')
    expect(isSelectableDate('2026-11-22', now)).toBe(true)
    expect(isSelectableDate('2026-11-23', now)).toBe(false)
    expect(isSelectableDate('2026-12-01', now)).toBe(false)
    expect(isSelectableDate('', now)).toBe(false)
    expect(isSelectableDate('2026/11/22', now)).toBe(false)
  })

  it('文件名优先用后端给的', () => {
    expect(csvFileName('2026-11-22', 'reconciliation-2026-11-22.csv')).toBe('reconciliation-2026-11-22.csv')
    expect(csvFileName('2026-11-22', null)).toBe('reconciliation-2026-11-22.csv')
  })
})

describe('对账接口', () => {
  it('读 JSON、带令牌下载 CSV（令牌不进地址）、重新对账带 date 和 redo', async () => {
    const fetchImpl = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify(RESULT), { status: 200 }))
      .mockResolvedValueOnce(new Response('﻿日期,类型\n', {
        status: 200,
        headers: {
          'Content-Type': 'text/csv',
          'Content-Disposition': 'attachment; filename="reconciliation-2026-11-22.csv"',
        },
      }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ date: '2026-11-22', merchants: 2, imported: 2, failed: 0,
        skipped: 0, differences: 1 }), { status: 200 }))
    const api = createReconciliationApi(createApiClient({
      getToken: () => 'secret-token', onUnauthorized: vi.fn(), fetchImpl, newRequestId: () => 'r',
    }))

    await expect(api.read('2026-11-22')).resolves.toEqual(RESULT)
    const file = await api.downloadCsv('2026-11-22')
    expect(file.fileName).toBe('reconciliation-2026-11-22.csv')
    expect(await file.blob.text()).toContain('日期,类型')
    await api.run('2026-11-22', false)

    const urls = fetchImpl.mock.calls.map(([url]) => String(url))
    expect(urls).toEqual([
      '/api/v1/platform/trade/reconciliation?date=2026-11-22',
      '/api/v1/platform/trade/reconciliation?date=2026-11-22&format=CSV',
      '/api/v1/platform/trade/reconciliation/runs?date=2026-11-22&redo=false',
    ])
    expect(urls.join(' ')).not.toContain('secret-token')
    expect(fetchImpl.mock.calls[1]![1]!.headers).toMatchObject({ Authorization: 'Bearer secret-token' })
    expect(fetchImpl.mock.calls[2]![1]!.method).toBe('POST')
  })

  it('账单没导入完的错误码有中文说明', () => {
    expect(errorMessage(new ApiRequestError(409, 'TRADE_RECONCILIATION_NOT_READY', '', 't'))).toContain('重新对账')
  })
})

describe('对账页面', () => {
  function fakeApi(overrides: Partial<ReconciliationApi> = {}): ReconciliationApi {
    return {
      read: vi.fn().mockResolvedValue(RESULT),
      downloadCsv: vi.fn().mockResolvedValue({ blob: new Blob(['x']), fileName: 'reconciliation-2026-11-22.csv' }),
      run: vi.fn(),
      ...overrides,
    }
  }

  it('进来读昨天的结果，显示汇总和差异（金额按元）；下载交给浏览器保存', async () => {
    const api = fakeApi()
    const save = vi.fn()
    const page = mount(ReconciliationPage, { props: { api, save }, global: { plugins: [ElementPlus] } })
    await settle()

    expect(api.read).toHaveBeenCalledWith(defaultReconciliationDate())
    expect(page.text()).toContain('12 笔')
    expect(page.text()).toContain('金额不一致')
    expect(page.text()).toContain('¥1,299.00')
    expect(page.text()).toContain('¥1,298.00')

    const button = page.findAll('button').find((item) => item.text().includes('下载差异表'))!
    await button.trigger('click')
    await settle()
    expect(api.downloadCsv).toHaveBeenCalledWith(defaultReconciliationDate())
    expect(save).toHaveBeenCalledWith(expect.any(Blob), 'reconciliation-2026-11-22.csv')
    page.unmount()
  })

  it('账单还没导入完：提示并给“重新对账”；别的错误给重新加载', async () => {
    const notReady = mount(ReconciliationPage, {
      props: { api: fakeApi({ read: vi.fn().mockRejectedValue(
        new ApiRequestError(409, 'TRADE_RECONCILIATION_NOT_READY', '', 't')) }) },
      global: { plugins: [ElementPlus] },
    })
    await settle()
    expect(notReady.text()).toContain('这一天的账单还没全部导入')
    expect(notReady.findAll('button').filter((item) => item.text() === '重新对账').length).toBeGreaterThan(1)
    notReady.unmount()

    const failed = mount(ReconciliationPage, {
      props: { api: fakeApi({ read: vi.fn().mockRejectedValue(
        new ApiRequestError(503, 'COMMON_SERVICE_UNAVAILABLE', '', 't')) }) },
      global: { plugins: [ElementPlus] },
    })
    await settle()
    expect(failed.text()).toContain('没读到对账结果')
    expect(failed.text()).toContain('重新加载')
    failed.unmount()
  })

  it('没有差异时表格显示空态', async () => {
    const page = mount(ReconciliationPage, {
      props: { api: fakeApi({ read: vi.fn().mockResolvedValue({ ...RESULT, differences: [] }) }) },
      global: { plugins: [ElementPlus] },
    })
    await settle()
    expect(page.text()).toContain('这一天没有差异')
    page.unmount()
  })
})
