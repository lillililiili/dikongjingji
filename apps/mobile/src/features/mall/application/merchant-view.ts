import { padTwo, toBeijingParts } from '../../../shared/time/beijing-time'
import type { CityDirectory, CityServiceStatus } from '../../../shared/city/domain/city-directory'

import type { MallMerchantActivity, MallMerchantContent, MallMerchantContentKind, MallMerchantPage } from '../domain/merchant'
import { MALL_MERCHANT_KIND_LABELS, formatMallAmount } from '../presentation/shared/mall-format'

/**
 * 商家主页怎么写（画板 Merchant 的商家帧）。每一行只来自服务端的商家资料；没有资料的行不显示，
 * 可售城市的名称取 App 已有的平台城市目录。
 */

export interface MerchantInfoRow {
  key: string
  label: string
  value: string
}

export type CityLookup = (cityCode: string) => { name: string; status: CityServiceStatus | null } | null

export function cityLookupFrom(directory: CityDirectory | null): CityLookup {
  if (!directory) return () => null
  const cities = new Map<string, { name: string; status: CityServiceStatus | null }>()
  for (const province of directory.provinces) {
    for (const city of province.cities) cities.set(city.code, { name: city.name, status: city.serviceStatus ?? null })
  }
  for (const city of directory.hotCities) {
    if (!cities.has(city.code)) cities.set(city.code, { name: city.name, status: city.serviceStatus ?? null })
  }
  return (cityCode) => cities.get(cityCode) ?? null
}

// "东营、济南（筹备）· 全国发货"：目录里查不到名称的城市不写编码，筹备中的城市注“筹备”；
// 有不分城市的供给时加“全国发货”，商家资料写了可售说明时用它。
export function saleCitiesText(page: MallMerchantPage, lookup: CityLookup): string | null {
  const names = page.saleCityCodes.flatMap((code) => {
    const city = lookup(code)
    if (!city) return []
    return [city.status === 'PREPARING' ? `${city.name}（筹备）` : city.name]
  })
  const scope = page.profile?.saleScopeNote ?? (page.nationwide ? '全国发货' : null)
  const cities = names.join('、')
  if (cities && scope) return `${cities} · ${scope}`
  return cities || scope
}

export function merchantInfoRows(page: MallMerchantPage, lookup: CityLookup): MerchantInfoRow[] {
  const rows: MerchantInfoRow[] = []
  if (page.profile?.businessEntity) rows.push({ key: 'entity', label: '经营主体', value: page.profile.businessEntity })
  const cities = saleCitiesText(page, lookup)
  if (cities) rows.push({ key: 'cities', label: '可售城市', value: cities })
  if (page.profile?.authorizationUntil) rows.push({ key: 'until', label: '授权有效期', value: `至 ${page.profile.authorizationUntil}` })
  return rows
}

// 头像：名称的第一个字（画板的字形头像）。
export function merchantGlyph(name: string): string {
  return Array.from(name.trim())[0] ?? '商'
}

export function merchantKindLabel(page: MallMerchantPage): string | null {
  return page.kind ? MALL_MERCHANT_KIND_LABELS[page.kind] : null
}

export type MerchantTab = 'GOODS' | 'ACTS' | 'POSTS' | 'QUAL'

export const MERCHANT_TABS: readonly MerchantTab[] = ['GOODS', 'ACTS', 'POSTS', 'QUAL']

export function merchantTabLabel(tab: MerchantTab, page: MallMerchantPage): string {
  switch (tab) {
    case 'GOODS': return `在售 ${page.productCount}`
    case 'ACTS': return `活动 ${page.activityCount}`
    case 'POSTS': return `内容 ${page.contentCount}`
    case 'QUAL': return '资质'
  }
}

export interface MerchantQualRow {
  key: string
  label: string
  value: string
  // 已审核是绿字。
  tone: 'PLAIN' | 'GREEN' | 'MUTED'
}

// 资质页签：经营主体、营业执照（只看平台的机构资质审核）、授权有效期、品类。
export function merchantQualRows(page: MallMerchantPage): MerchantQualRow[] {
  const rows: MerchantQualRow[] = []
  if (page.profile?.businessEntity) rows.push({ key: 'entity', label: '经营主体', value: page.profile.businessEntity, tone: 'PLAIN' })
  rows.push({
    key: 'license',
    label: '营业执照',
    value: page.qualificationReviewed ? '已审核' : '未审核',
    tone: page.qualificationReviewed ? 'GREEN' : 'MUTED',
  })
  if (page.profile?.authorizationUntil) rows.push({ key: 'until', label: '授权有效期', value: `至 ${page.profile.authorizationUntil}`, tone: 'PLAIN' })
  if (page.profile?.categoryNote) rows.push({ key: 'category', label: '品类', value: page.profile.categoryNote, tone: 'PLAIN' })
  return rows
}

// "11/08 10:00 · 东营低空与机器人体验馆"
export function merchantActivityWhen(activity: MallMerchantActivity): string {
  const parts = toBeijingParts(new Date(activity.startsAt))
  const time = `${padTwo(parts.month)}/${padTwo(parts.day)} ${padTwo(parts.hour)}:${padTwo(parts.minute)}`
  return activity.venueName ? `${time} · ${activity.venueName}` : time
}

export function merchantActivityPrice(activity: MallMerchantActivity): { text: string; free: boolean } {
  if (activity.priceMode === 'FREE') return { text: '免费', free: true }
  if (activity.priceMode === 'INQUIRY' || activity.priceAmountMinor === null) return { text: '询价', free: false }
  return { text: `${formatMallAmount(activity.priceAmountMinor)}/人`, free: false }
}

export const MERCHANT_CONTENT_KIND_LABELS: Record<MallMerchantContentKind, string> = {
  CASE: '案例',
  KNOWLEDGE: '知识',
  VIDEO: '视频',
}

// 内容卡下面的小字：类型，商家发布的内容标“广告”，示例内容标“示例”（AGENTS M19）。
export function merchantContentTags(content: MallMerchantContent): string[] {
  const tags = [MERCHANT_CONTENT_KIND_LABELS[content.kind]]
  if (content.sponsored) tags.push('广告')
  if (content.sample) tags.push('示例')
  return tags
}
