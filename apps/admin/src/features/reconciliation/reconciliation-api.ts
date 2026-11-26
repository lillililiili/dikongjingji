import type { ApiClient } from '@/core/http/api-client'
import { registerErrorMessages } from '@/core/http/api-error'

// 契约 contracts/openapi-trade.yaml 的平台运营对账接口（TradePlatformController，E06）：
// - GET  /api/v1/platform/trade/reconciliation?date=…（JSON：汇总和差异；format=CSV：下载差异表，UTF-8 带 BOM）
// - POST /api/v1/platform/trade/reconciliation/runs?date=…&redo=…（立刻对某一天跑一次，只能是北京时间今天以前）
// 商家侧没有对账接口（契约里 /api/v1/provider/trade/… 只有订单、发货、核销、退款和收款账户），所以只有平台运营的这一页。

registerErrorMessages({
  TRADE_RECONCILIATION_NOT_READY: '这一天的微信账单还没全部导入（T 日账单 T+1 日 10 点后才有，或导入失败），可以点“重新对账”再试',
  TRADE_CHANNEL_UNAVAILABLE: '微信支付暂时连不上，请稍后再试',
})

export type DifferenceKind = 'ONLY_IN_CHANNEL' | 'ONLY_IN_PLATFORM' | 'AMOUNT_MISMATCH' | 'STATUS_MISMATCH'

export interface ReconciliationDifference {
  kind: DifferenceKind
  subMchid: string
  outTradeNo: string | null
  outRefundNo: string | null
  platformAmountMinor: number | null
  channelAmountMinor: number | null
}

export interface Reconciliation {
  /** yyyy-MM-dd，北京时间的那一天。 */
  date: string
  importedAt: string | null
  /** 逐笔核对一致的笔数。 */
  matchedCount: number
  differences: ReconciliationDifference[]
}

export interface ReconciliationRun {
  date: string
  /** 这一天要对账的子商户数。 */
  merchants: number
  imported: number
  failed: number
  skipped: number
  differences: number
}

export const DIFFERENCE_LABELS: Record<DifferenceKind, string> = {
  ONLY_IN_CHANNEL: '微信有、平台没有',
  ONLY_IN_PLATFORM: '平台记为成功、微信没有',
  AMOUNT_MISMATCH: '金额不一致',
  STATUS_MISMATCH: '状态不一致',
}

const BASE = '/api/v1/platform/trade/reconciliation'
const DAY_MS = 24 * 60 * 60 * 1000
/** 北京时间比 UTC 早 8 小时，没有夏令时。 */
const BEIJING_OFFSET_MS = 8 * 60 * 60 * 1000

/** 北京时间的“今天”（yyyy-MM-dd），不跟电脑时区走。 */
export function beijingToday(now: number = Date.now()): string {
  return new Date(now + BEIJING_OFFSET_MS).toISOString().slice(0, 10)
}

/** 默认看昨天（北京时间）：T 日账单 T+1 日 10 点后才有。 */
export function defaultReconciliationDate(now: number = Date.now()): string {
  return beijingToday(now - DAY_MS)
}

/** 能选的日期：今天以前（北京时间）。今天和以后的账单还没有，后端重跑也会拒绝。 */
export function isSelectableDate(date: string, now: number = Date.now()): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(date) && date < beijingToday(now)
}

/** 下载的文件名：后端给了就用后端的（reconciliation-<date>.csv），没给按日期拼。 */
export function csvFileName(date: string, fromServer: string | null): string {
  return fromServer || `reconciliation-${date}.csv`
}

export function createReconciliationApi(http: ApiClient) {
  return {
    /** 这一天的汇总和差异；账单没全部导入时 409 TRADE_RECONCILIATION_NOT_READY。 */
    read(date: string): Promise<Reconciliation> {
      return http.get<Reconciliation>(BASE, { date })
    },
    /**
     * 下载这一天的差异表 CSV：带登录令牌取回 Blob（令牌在 Authorization 头里，不放进地址），由页面交给浏览器保存。
     */
    downloadCsv(date: string): Promise<{ blob: Blob, fileName: string | null }> {
      const query = new URLSearchParams({ date, format: 'CSV' })
      return http.download(`${BASE}?${query.toString()}`)
    },
    /** 立刻对这一天跑一次；redo=true 时已导入的子商户也重新下载覆盖。 */
    run(date: string, redo: boolean): Promise<ReconciliationRun> {
      return http.post<ReconciliationRun>(`${BASE}/runs`, undefined, { query: { date, redo } })
    },
  }
}

export type ReconciliationApi = ReturnType<typeof createReconciliationApi>

/** 把 Blob 交给浏览器保存（临时对象地址，过一会儿释放）。 */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}
