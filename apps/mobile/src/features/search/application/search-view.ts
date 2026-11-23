import { resolveActivityPreviewCover } from '../../activity/data/preview/activity-preview-covers'
import type { ActivityCard } from '../../activity/domain/activity'
import { ACTIVITY_TYPE_LABELS, formatActivityPrice } from '../../activity/presentation/shared/activity-format'
import { resolveArticlePreviewCover } from '../../content/data/preview/content-preview-covers'
import type { ContentArticleCard, ContentArticleKind } from '../../content/domain/content'
import { buildContentArticleUrl, buildContentVideoUrl, contentRefTargetUrl } from '../../content/navigation/content-routes'
import { resolveExperiencePreviewCover } from '../../experience/data/preview/experience-preview-covers'
import type { VenueCard } from '../../experience/domain/experience'
import { formatVenueCardNote } from '../../experience/presentation/shared/experience-format'
import { buildDemandPublishUrl } from '../../demand/navigation/demand-routes'
import type { InspectionCatalogService } from '../../inspection/presentation/catalog/model/inspection-catalog'
import { formatCatalogPrice } from '../../inspection/presentation/catalog/model/inspection-catalog'
import { resolveMallPreviewCover } from '../../mall/data/preview/mall-preview-covers'
import type { MallProductCard } from '../../mall/domain/mall'
import {
  MALL_BADGE_LABELS,
  MALL_CATEGORY_LABELS,
  formatMallCardPrice,
  formatMallSellers,
} from '../../mall/presentation/shared/mall-format'
import { resolveProServicePreviewCover } from '../../proservice/data/preview/proservice-preview-covers'
import { PRO_SERVICE_TYPE_INFO, formatProServicePrice } from '../../proservice/application/proservice-view'
import type { ProServiceCard } from '../../proservice/domain/proservice'
import {
  SEARCH_SCOPE_LABELS,
  type SearchResult,
  type SearchScope,
} from '../domain/search'

// What boards SearchPre (联想) and MallSearch (结果) draw from a search result. Every value comes
// from the card of its own list and the formatting its own module uses, so a row says what the
// channel's list says.

export interface HighlightParts {
  pre: string
  hit: string
  post: string
}

/**
 * Cuts the matched part out of a name for the orange highlight (board SearchPre `cut`): the match
 * ignores spaces, so "3分钟" finds "3 分钟看懂编程机器人", and the cut is put back on the original
 * text. Letters match in any case. No match leaves the whole text in `pre`.
 */
export function highlightKeyword(text: string, keyword: string): HighlightParts {
  const key = keyword.replace(/\s+/g, '').toLowerCase()
  if (!key) return { pre: text, hit: '', post: '' }
  const positions: number[] = []
  let compact = ''
  for (let index = 0; index < text.length; index += 1) {
    if (/\s/.test(text[index])) continue
    positions.push(index)
    compact += text[index].toLowerCase()
  }
  const at = compact.indexOf(key)
  if (at < 0) return { pre: text, hit: '', post: '' }
  const from = positions[at]
  const to = positions[at + key.length - 1] + 1
  return { pre: text.slice(0, from), hit: text.slice(from, to), post: text.slice(to) }
}

export type SearchRowKind = 'PRODUCT' | 'INSPECTION' | 'PRO_SERVICE' | 'ACTIVITY' | 'CONTENT' | 'VENUE'

// The tag colours of the suggestion rows (board SearchPre `tones`).
export type SearchTone = 'PRODUCT' | 'SERVICE' | 'ACTIVITY' | 'CONTENT' | 'VENUE'

export interface SearchRow {
  key: string
  kind: SearchRowKind
  tone: SearchTone
  // 商品 / 服务 / 活动 / 内容 / 场馆
  typeLabel: string
  name: string
  title: HighlightParts
  // The grey line under the title; empty when the card has none.
  sub: string
  // The orange price (商品, 活动); null when the row shows none.
  price: string | null
  // The right-hand note of a product (“2 家在售”).
  sellers: string
  // The small tag in front of an activity title (培训) or a content title (案例).
  tag: string
  sponsored: boolean
  image: string | null
  imageAlt: string
  url: string
  // 提需求 on a service row (board MallSearch).
  demandUrl: string | null
}

export interface SearchGroupView {
  scope: Exclude<SearchScope, 'ALL'>
  label: string
  total: number
  rows: SearchRow[]
}

const CONTENT_KIND_LABELS: Record<ContentArticleKind, string> = {
  CASE: '案例',
  KNOWLEDGE: '知识',
  VIDEO: '视频',
}

function productRow(card: MallProductCard, keyword: string, cityCode: string): SearchRow {
  const price = formatMallCardPrice(card)
  return {
    key: `product:${card.productId}`,
    kind: 'PRODUCT',
    tone: 'PRODUCT',
    typeLabel: '商品',
    name: card.name,
    title: highlightKeyword(card.name, keyword),
    sub: [MALL_CATEGORY_LABELS[card.category], card.experienceBadge ? MALL_BADGE_LABELS[card.experienceBadge] : '']
      .filter(Boolean).join(' · '),
    price: `${price.amount}${price.unit}${price.from}`,
    sellers: formatMallSellers(card),
    tag: '',
    sponsored: false,
    image: card.coverImage?.url ?? resolveMallPreviewCover(card.productId)?.url ?? null,
    imageAlt: card.coverImage?.alt ?? card.name,
    url: contentRefTargetUrl({ type: 'PRODUCT', refId: card.productId }, cityCode),
    demandUrl: null,
  }
}

function inspectionRow(service: InspectionCatalogService, keyword: string, cityCode: string): SearchRow {
  const price = formatCatalogPrice(service.price)
  return {
    key: `inspection:${service.id}`,
    kind: 'INSPECTION',
    tone: 'SERVICE',
    typeLabel: '服务',
    name: service.title,
    title: highlightKeyword(service.title, keyword),
    // "¥880/次", as the 热门巡检服务 cards write it.
    sub: [`${price.amountLabel}${price.unitLabel ? `/${price.unitLabel}` : ''}`, `巡检 · ${service.categoryLabel}`].join(' · '),
    price: null,
    sellers: '',
    tag: '',
    sponsored: false,
    image: service.imageUrl || null,
    imageAlt: service.title,
    url: contentRefTargetUrl({ type: 'INSPECTION_SERVICE', refId: service.id }, cityCode),
    demandUrl: buildDemandPublishUrl({ type: 'INSPECTION' }),
  }
}

function proServiceRow(service: ProServiceCard, keyword: string, cityCode: string): SearchRow {
  const price = formatProServicePrice(service.price)
  return {
    key: `pro:${service.serviceId}`,
    kind: 'PRO_SERVICE',
    tone: 'SERVICE',
    typeLabel: '服务',
    name: service.title,
    title: highlightKeyword(service.title, keyword),
    sub: [`${price.amount}${price.quote ? '' : price.unit}`, PRO_SERVICE_TYPE_INFO[service.type].label].join(' · '),
    price: null,
    sellers: '',
    tag: '',
    sponsored: false,
    image: service.coverUrl ?? resolveProServicePreviewCover(service.type, service.category.code),
    imageAlt: service.title,
    url: contentRefTargetUrl({ type: 'PRO_SERVICE', refId: service.serviceId }, cityCode),
    demandUrl: buildDemandPublishUrl({ type: service.type }),
  }
}

function activityRow(card: ActivityCard, keyword: string, cityCode: string): SearchRow {
  return {
    key: `activity:${card.activityId}`,
    kind: 'ACTIVITY',
    tone: 'ACTIVITY',
    typeLabel: '活动',
    name: card.title,
    title: highlightKeyword(card.title, keyword),
    sub: card.venueName,
    price: formatActivityPrice(card.price),
    sellers: '',
    tag: ACTIVITY_TYPE_LABELS[card.type],
    sponsored: false,
    image: card.coverImage?.url ?? resolveActivityPreviewCover(card.activityId),
    imageAlt: card.coverImage?.alt ?? card.title,
    url: contentRefTargetUrl({ type: 'ACTIVITY', refId: card.activityId }, cityCode),
    demandUrl: null,
  }
}

function contentRow(card: ContentArticleCard, keyword: string, cityCode: string): SearchRow {
  const preview = resolveArticlePreviewCover(card.articleId)
  return {
    key: `content:${card.articleId}`,
    kind: 'CONTENT',
    tone: 'CONTENT',
    typeLabel: '内容',
    name: card.title,
    title: highlightKeyword(card.title, keyword),
    sub: card.author.name,
    price: null,
    sellers: '',
    tag: CONTENT_KIND_LABELS[card.kind],
    sponsored: card.sponsored,
    image: card.coverImage?.url ?? preview?.url ?? null,
    imageAlt: card.coverImage?.alt ?? preview?.alt ?? card.title,
    url: card.kind === 'VIDEO'
      ? buildContentVideoUrl(card.articleId, cityCode)
      : buildContentArticleUrl(card.articleId, cityCode),
    demandUrl: null,
  }
}

function venueRow(card: VenueCard, keyword: string, cityCode: string): SearchRow {
  return {
    key: `venue:${card.venueId}`,
    kind: 'VENUE',
    tone: 'VENUE',
    typeLabel: '场馆',
    name: card.name,
    title: highlightKeyword(card.name, keyword),
    sub: formatVenueCardNote(card) || card.address,
    price: null,
    sellers: '',
    tag: '',
    sponsored: false,
    image: card.coverImage?.url ?? resolveExperiencePreviewCover(card.venueId),
    imageAlt: card.coverImage?.alt ?? card.name,
    url: contentRefTargetUrl({ type: 'VENUE', refId: card.venueId }, cityCode),
    demandUrl: null,
  }
}

/** The groups of a result in the board's order (商品, 服务, 活动, 内容, 场馆); groups outside the scope are left out. */
export function describeSearchGroups(result: SearchResult): SearchGroupView[] {
  const { keyword, cityCode } = result
  const groups: SearchGroupView[] = []
  const push = (scope: SearchGroupView['scope'], total: number, rows: SearchRow[]): void => {
    groups.push({ scope, label: SEARCH_SCOPE_LABELS[scope], total, rows })
  }
  if (result.products) push('PRODUCT', result.products.total, result.products.items.map((card) => productRow(card, keyword, cityCode)))
  if (result.services) {
    push('SERVICE', result.services.total, result.services.items.map((hit) => hit.kind === 'INSPECTION'
      ? inspectionRow(hit.service, keyword, cityCode)
      : proServiceRow(hit.service, keyword, cityCode)))
  }
  if (result.activities) push('ACTIVITY', result.activities.total, result.activities.items.map((card) => activityRow(card, keyword, cityCode)))
  if (result.contents) push('CONTENT', result.contents.total, result.contents.items.map((card) => contentRow(card, keyword, cityCode)))
  if (result.venues) push('VENUE', result.venues.total, result.venues.items.map((card) => venueRow(card, keyword, cityCode)))
  return groups
}

export function hasSearchResults(groups: readonly SearchGroupView[]): boolean {
  return groups.some((group) => group.rows.length > 0)
}

// 联想 on board SearchPre: a few names straight to their pages, filtered by the scope tab.
export const SEARCH_SUGGESTION_LIMIT = 6

export function describeSearchSuggestions(result: SearchResult, scope: SearchScope): SearchRow[] {
  return describeSearchGroups(result)
    .filter((group) => scope === 'ALL' || group.scope === scope)
    .flatMap((group) => group.rows)
    .slice(0, SEARCH_SUGGESTION_LIMIT)
}

// "东营 · 结果按本城可用范围显示"
export function searchCityLine(cityName: string | null | undefined): string {
  const name = cityName ?? ''
  const short = name.length > 2 && name.endsWith('市') ? name.slice(0, -1) : name
  return short ? `${short} · 结果按本城可用范围显示` : '结果按本城可用范围显示'
}
