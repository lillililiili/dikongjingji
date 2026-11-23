import { charCount, formatMoney, minorToYuanText, yuanToMinor } from '@/core/ui/format'

// 报价明细和对比项（契约 contracts/openapi-demand.yaml 1.3.0 的 QuoteLine、QuoteLineRequest，原型 PlanCompare）。
// 服务订单（openapi-service-order.yaml 的 ServiceOrderLine、ServiceOrderQuote）记的是同样的快照，也用这里的显示方法。

/** 报价明细的一行：按次计的“数量 × 单价 = 金额”，不按次计的只有金额（数量、单位、单价为 null）。 */
export interface QuoteLine {
  name: string
  quantity: number | null
  unit: string | null
  unitAmountMinor: number | null
  amountMinor: number
}

/** 对比项（都可以不填，旧报价没有）。 */
export interface QuoteExtras {
  team: string | null
  nightWork: string | null
  reportHours: number | null
  /** 影像存档天数；0 表示不含。 */
  archiveDays: number | null
  /** 首次进场日期（北京时间 yyyy-MM-dd）。 */
  firstVisitDate: string | null
}

export const LINE_LIMITS = {
  lines: 10,
  name: 20,
  unit: 4,
  maxQuantity: 999,
  maxAmountMinor: 10_000_000_000,
  team: 20,
  nightWork: 20,
  maxReportHours: 720,
  maxArchiveDays: 3650,
} as const

/** 页面上的一行（输入框里都是文字，金额按元）。 */
export interface LineForm {
  /** 只给 v-for 用的本地编号。 */
  key: number
  name: string
  quantity: string
  unit: string
  unitYuan: string
  /** 只填金额（不按次计）时用；按次计时由数量 × 单价算出。 */
  amountYuan: string
}

let nextKey = 0

export function newLineForm(): LineForm {
  return { key: ++nextKey, name: '', quantity: '', unit: '', unitYuan: '', amountYuan: '' }
}

export function lineFormsFrom(lines: readonly QuoteLine[]): LineForm[] {
  return lines.map((line) => ({
    key: ++nextKey,
    name: line.name,
    quantity: line.quantity === null ? '' : String(line.quantity),
    unit: line.unit ?? '',
    unitYuan: line.unitAmountMinor === null ? '' : minorToYuanText(line.unitAmountMinor),
    amountYuan: minorToYuanText(line.amountMinor),
  }))
}

/** 这一行填了数量、单位、单价里的任何一项，就按“数量 × 单价”算。 */
export function isPerUnit(line: LineForm): boolean {
  return line.quantity.trim() !== '' || line.unit.trim() !== '' || line.unitYuan.trim() !== ''
}

function parseQuantity(text: string): number | null {
  const value = text.trim()
  if (!/^\d{1,3}$/.test(value)) return null
  const quantity = Number(value)
  return quantity >= 1 && quantity <= LINE_LIMITS.maxQuantity ? quantity : null
}

/** 这一行的金额（分）：按次计的是数量 × 单价，否则是填的金额；填得不对时为 null。 */
export function lineAmountMinor(line: LineForm): number | null {
  if (!isPerUnit(line)) return yuanToMinor(line.amountYuan)
  const quantity = parseQuantity(line.quantity)
  const unit = yuanToMinor(line.unitYuan)
  if (quantity === null || unit === null) return null
  return quantity * unit
}

/** 明细合计（分）；有一行算不出来时为 null，没有明细时为 0。 */
export function linesTotalMinor(lines: readonly LineForm[]): number | null {
  let total = 0
  for (const line of lines) {
    const amount = lineAmountMinor(line)
    if (amount === null) return null
    total += amount
  }
  return total
}

export type LineResult = { ok: true, lines: QuoteLine[] } | { ok: false, message: string }

const CONTROL = /[\u0000-\u001f\u007f]/

/** 按契约检查每一行，返回请求里的明细或第一条错误。 */
export function buildLines(forms: readonly LineForm[]): LineResult {
  if (forms.length > LINE_LIMITS.lines) return { ok: false, message: `报价明细最多 ${LINE_LIMITS.lines} 行` }
  const lines: QuoteLine[] = []
  for (const [index, form] of forms.entries()) {
    const at = `明细第 ${index + 1} 行`
    const name = form.name.trim()
    if (!name) return { ok: false, message: `${at}要写名称` }
    if (charCount(name) > LINE_LIMITS.name) return { ok: false, message: `${at}的名称最多 ${LINE_LIMITS.name} 个字` }
    if (CONTROL.test(name)) return { ok: false, message: `${at}的名称里不能有换行或特殊字符` }
    if (isPerUnit(form)) {
      const unit = form.unit.trim()
      if (form.quantity.trim() === '' || unit === '' || form.unitYuan.trim() === '') {
        return { ok: false, message: `${at}：数量、单位、单价要么都填，要么都不填（只填金额）` }
      }
      const quantity = parseQuantity(form.quantity)
      if (quantity === null) return { ok: false, message: `${at}的数量要是 1–${LINE_LIMITS.maxQuantity} 的整数` }
      if (charCount(unit) > LINE_LIMITS.unit) return { ok: false, message: `${at}的单位最多 ${LINE_LIMITS.unit} 个字` }
      const unitMinor = yuanToMinor(form.unitYuan)
      if (unitMinor === null || unitMinor < 1) return { ok: false, message: `${at}的单价要大于 0，最多两位小数` }
      const amount = quantity * unitMinor
      if (amount > LINE_LIMITS.maxAmountMinor) return { ok: false, message: `${at}的金额超过上限` }
      lines.push({ name, quantity, unit, unitAmountMinor: unitMinor, amountMinor: amount })
    } else {
      const amount = yuanToMinor(form.amountYuan)
      if (amount === null || amount < 1) return { ok: false, message: `${at}的金额要大于 0，最多两位小数` }
      if (amount > LINE_LIMITS.maxAmountMinor) return { ok: false, message: `${at}的金额超过上限` }
      lines.push({ name, quantity: null, unit: null, unitAmountMinor: null, amountMinor: amount })
    }
  }
  return { ok: true, lines }
}

/** 一行的“6 次 × ¥1,800.00”；不按次计的为空串。 */
export function lineQuantityLabel(line: QuoteLine): string {
  if (line.quantity === null || line.unitAmountMinor === null) return ''
  return `${line.quantity} ${line.unit ?? ''} × ${formatMoney(line.unitAmountMinor)}`
}

/** 对比项里填了的几行（没填的不显示，和 App 一致）。 */
export function extrasRows(extras: QuoteExtras): Array<{ label: string, value: string }> {
  const rows: Array<{ label: string, value: string }> = []
  if (extras.team) rows.push({ label: '服务团队', value: extras.team })
  if (extras.nightWork) rows.push({ label: '夜间作业', value: extras.nightWork })
  if (extras.reportHours !== null) rows.push({ label: '报告时效', value: `作业后 ${extras.reportHours} 小时内` })
  if (extras.archiveDays !== null) {
    rows.push({ label: '影像存档', value: extras.archiveDays === 0 ? '不含' : `${extras.archiveDays} 天` })
  }
  if (extras.firstVisitDate) rows.push({ label: '首次进场', value: extras.firstVisitDate })
  return rows
}
