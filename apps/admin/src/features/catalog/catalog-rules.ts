import type { FormItemRule, FormRules } from 'element-plus'

import { charCount } from '@/core/ui/format'
import { maxChars, required } from '@/core/ui/form-rules'
import { yuanToMinor } from '@/core/ui/money'

import { CATALOG_LIMITS as L, type MallSaleMode, priceAllowed, priceUnitsFor } from './catalog-api'

// 和后端商城模块 MallCatalogOpsService 的校验一致（契约 openapi-ops-catalog.yaml 1.0.0）；后端还会再校验一次。

export const createProductRules: FormRules = {
  category: [required('类目', 'change')],
  name: [required('商品名称'), maxChars('商品名称', L.name)],
  saleMode: [required('交易方式', 'change')],
}

export const productRules: FormRules = {
  category: [required('类目', 'change')],
  name: [required('商品名称'), maxChars('商品名称', L.name)],
  summary: [maxChars('一句话介绍', L.summary)],
}

export const skuRules: FormRules = {
  name: [required('规格名称'), maxChars('规格名称', L.skuName)],
}

/** 一个新卖点标签能不能加：去空白后 1–12 字、不重复、最多 5 个。返回错误说明，没问题返回 null。 */
export function sellingPointProblem(existing: readonly string[], candidate: string): string | null {
  const text = candidate.trim()
  if (text === '') return '请填写卖点'
  if (charCount(text) > L.sellingPoint) return `每个卖点最多 ${L.sellingPoint} 个字`
  if (existing.length >= L.sellingPoints) return `最多 ${L.sellingPoints} 个卖点`
  if (existing.includes(text)) return '这个卖点已经有了'
  return null
}

/** 图片地址：受控地址（上传得到的）或带主机名的 https 地址；后端最后判断是不是受控的。 */
export function imageUrlProblem(url: string): string | null {
  const text = url.trim()
  if (text === '') return null
  if (text.length > L.url) return `地址最多 ${L.url} 个字符`
  try {
    const parsed = new URL(text)
    if (parsed.protocol === 'https:' && parsed.hostname) return null
    // 开发环境上传得到的受控地址是 http 的，也放行，由后端判断。
    if (parsed.protocol === 'http:' && parsed.pathname.startsWith('/api/v1/mall/media/')) return null
  } catch {
    // 落到下面的提示
  }
  return '只能填上传得到的地址或 https 图片地址'
}

/** 价格（元）输入：询价商品不能填；购买和租赁可以先空着（启用前再填）；两位小数以内的非负数。 */
export function priceProblem(saleMode: MallSaleMode, yuan: number | null | undefined): string | null {
  if (yuan === null || yuan === undefined || String(yuan).trim() === '') return null
  if (!priceAllowed(saleMode)) return '询价商品不标价'
  const minor = yuanToMinor(yuan)
  if (minor === null) return '价格是非负数，最多两位小数'
  if (minor > L.priceMinor) return '价格太大了'
  return null
}

/** 价格单位必须和交易方式相配。 */
export function priceUnitProblem(saleMode: MallSaleMode, unit: string): string | null {
  return (priceUnitsFor(saleMode) as string[]).includes(unit) ? null : '价格单位和交易方式不相配'
}

/** 不能快递时必须有自提点。 */
export function pickupProblem(expressAvailable: boolean, pickupPoint: string | null | undefined): string | null {
  const text = (pickupPoint ?? '').trim()
  if (!expressAvailable && text === '') return '不能快递时要填自提点'
  if (charCount(text) > L.pickupPoint) return `自提点最多 ${L.pickupPoint} 个字`
  return null
}

function fromProblem(problem: () => string | null): FormItemRule {
  return {
    trigger: 'blur',
    validator: (_rule, _value, callback) => {
      const message = problem()
      callback(message ? new Error(message) : undefined)
    },
  }
}

/** 供给弹窗的规则：依赖表单里的交易方式和是否快递，所以按当前表单造。 */
export function offerRules(form: { expressAvailable: boolean, pickupPoint: string, priceYuan: number | null,
  priceUnit: string }, saleMode: () => MallSaleMode, creating: boolean): FormRules {
  const rules: FormRules = {
    merchantKind: [required('商家类型', 'change')],
    priceUnit: [required('价格单位', 'change'), fromProblem(() => priceUnitProblem(saleMode(), form.priceUnit))],
    priceYuan: [fromProblem(() => priceProblem(saleMode(), form.priceYuan))],
    pickupPoint: [fromProblem(() => pickupProblem(form.expressAvailable, form.pickupPoint))],
    deliveryNote: [maxChars('供给说明', L.note)],
    shippingNote: [maxChars('配送说明', L.note)],
    fulfilledFrom: [maxChars('发货地', L.fulfilledFrom)],
  }
  if (creating) {
    rules.skuId = [required('规格', 'change')]
    rules.merchantId = [required('商家', 'change')]
  }
  return rules
}
