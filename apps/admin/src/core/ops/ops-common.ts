import { ApiRequestError } from '@/core/http/api-error'

/**
 * 运营端第三部分（活动与场次、场馆、发现内容、直播、服务目录）共用的形状和小工具，和后端
 * com.lowaltitude.inspection.ops.common 一致。
 */

/** 一条改动记录（各块契约里的 OpsChange）。 */
export interface OpsChange {
  changeId: string
  /** 改的对象类型，比如 ACTIVITY、SESSION、VENUE（各块契约列出）。 */
  subjectType: string
  subjectId: string
  /** 动作：CREATE、UPDATE、PUBLISH、OFFLINE、DELETE 等（各块契约列出）。 */
  action: string
  summary: string
  operatorName: string
  changedAt: string
}

/** 常用动作的中文；各块有自己的动作时传 extra 覆盖或补充。 */
export const COMMON_ACTION_LABELS: Readonly<Record<string, string>> = {
  CREATE: '新建',
  UPDATE: '修改',
  PUBLISH: '上线',
  OFFLINE: '下线',
  DELETE: '删除',
  STOP_SALE: '停售',
  RESUME_SALE: '恢复售卖',
  REORDER: '调整排序',
  GENERATE: '批量生成',
}

export function actionLabel(action: string, extra: Readonly<Record<string, string>> = {}): string {
  return extra[action] ?? COMMON_ACTION_LABELS[action] ?? action
}

/** 上线前必填项没齐（422 OPS_PUBLISH_INCOMPLETE）时，后端在 details.missing 里给缺的字段键。 */
export const PUBLISH_INCOMPLETE = 'OPS_PUBLISH_INCOMPLETE'

export function missingFields(error: unknown): string[] {
  if (!(error instanceof ApiRequestError) || error.code !== PUBLISH_INCOMPLETE) return []
  const missing = error.details?.missing
  return Array.isArray(missing) ? missing.filter((key): key is string => typeof key === 'string') : []
}

/** 把缺的字段键翻成“还差：封面、场次”；没登记的键原样显示，免得漏看。 */
export function describeMissing(error: unknown, labels: Readonly<Record<string, string>>): string | null {
  const keys = missingFields(error)
  if (keys.length === 0) return null
  return `上线前还差：${keys.map((key) => labels[key] ?? key).join('、')}`
}

/** 内容状态的中文和标签颜色（DRAFT / PUBLISHED / OFFLINE 三态的块共用）。 */
export type PublishStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE'

export const PUBLISH_STATUS_OPTIONS: ReadonlyArray<{ value: PublishStatus, label: string, tag: 'info' | 'success' | 'warning' }> = [
  { value: 'DRAFT', label: '草稿', tag: 'info' },
  { value: 'PUBLISHED', label: '已上线', tag: 'success' },
  { value: 'OFFLINE', label: '已下线', tag: 'warning' },
]

export function publishStatusLabel(status: string): string {
  return PUBLISH_STATUS_OPTIONS.find((option) => option.value === status)?.label ?? status
}

export function publishStatusTag(status: string): 'info' | 'success' | 'warning' {
  return PUBLISH_STATUS_OPTIONS.find((option) => option.value === status)?.tag ?? 'info'
}

/** 金额：分 → “¥12.50”；后端一律用分（long）。 */
export function formatYuan(minor: number | null | undefined): string {
  if (minor === null || minor === undefined) return '—'
  return `¥${(minor / 100).toFixed(2)}`
}

/** 页面上填的“元”（最多两位小数）→ 分；不合格返回 null。 */
export function yuanToMinor(text: string | number | null | undefined): number | null {
  if (text === null || text === undefined) return null
  const value = String(text).trim()
  if (!/^\d{1,7}(\.\d{1,2})?$/.test(value)) return null
  const [whole, fraction = ''] = value.split('.')
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
}

export function minorToYuanText(minor: number | null | undefined): string {
  if (minor === null || minor === undefined) return ''
  return (minor / 100).toFixed(2).replace(/\.00$/, '')
}
