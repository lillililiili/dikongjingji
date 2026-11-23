import type { ActivityApi } from '../../activity/data/activity-api'
import {
  ACTIVITY_STATUS_LABELS,
  ACTIVITY_TYPE_LABELS,
  formatActivityPrice,
  formatActivityShortDateTime,
  isPerPersonPrice,
} from '../../activity/presentation/shared/activity-format'
import type { ActivityDetail } from '../../activity/domain/activity'
import type { ExperienceApi } from '../../experience/data/experience-api'
import { formatVenuePrice, formatVenueSessionTime } from '../../experience/presentation/shared/experience-format'
import type { VenueSession } from '../../experience/domain/experience'
import type { InspectionServiceDetail } from '../../inspection/domain/inspection-service-detail'
import type { MallApi } from '../../mall/data/mall-api'
import type { MallProductDetail } from '../../mall/domain/mall'
import type { ProServiceApi } from '../../proservice/data/proservice-api'
import { formatProServicePrice, formatProServiceYuan } from '../../proservice/application/proservice-view'
import { buildActivityDetailUrl } from '../../activity/navigation/activity-routes'
import { buildVenueDetailUrl } from '../../experience/navigation/experience-routes'
import { toBeijingDate } from '../../../shared/time/beijing-time'

import { buildMallProductUrl } from '../../mall/navigation/mall-routes'
import {
  resolveRefPreviewCover,
  resolveTopicActivityPreviewCover,
  resolveTopicMerchantPreview,
} from '../data/preview/content-preview-covers'
import type { ContentRef, ContentRefType, ContentTopicMerchantItem } from '../domain/content'
import { contentRefTargetUrl } from '../navigation/content-routes'
import type { Tone } from './content-view'

// The modules an article's mentions and a topic's blocks are read from, each with its own API.
export interface ContentLinkedApis {
  inspection: { loadDetail(serviceId: string, cityCode: string): Promise<InspectionServiceDetail> }
  proService: Pick<ProServiceApi, 'loadService'>
  mall: Pick<MallApi, 'loadProduct'>
  activity: Pick<ActivityApi, 'loadDetail'>
  experience: Pick<ExperienceApi, 'loadVenue' | 'loadSessions'>
}

export interface PriceView {
  // "¥1,299", "免费", "按项目报价".
  amount: string
  // "/天 起", "/人", "起" or ''.
  unit: string
}

/** One card of 内容中提到, made from what its own module says now. */
export interface ContentRefView {
  key: string
  type: ContentRefType
  refId: string
  tag: string
  tagTone: Tone
  // A uni-icons line icon for the tile when there is no picture.
  icon: string
  title: string
  price: PriceView | null
  actionLabel: string
  // Services carry the orange button (board ContentArticle); goods, activities and venues a plain one.
  primary: boolean
  imageUrl: string | null
  // The picture is drawn whole on a tint (a product cut-out, an illustration) instead of cropped.
  imageContain?: boolean
  // An illustration: smaller, on the orange tint.
  imagePadded?: boolean
  url: string
}

const TONES = {
  service: { background: '#e6f0ff', color: '#1d63c4' },
  goods: { background: '#fff0e3', color: '#ba470b' },
  activity: { background: '#eee7ff', color: '#6a4fe0' },
  venue: { background: '#e2f6ed', color: '#11804a' },
} satisfies Record<string, Tone>

const httpsOnly = (url: string | null | undefined): string | null =>
  url && /^https:\/\//i.test(url) ? url : null

const MALL_UNITS = { ITEM: '', DAY: '/天', HALF_DAY: '/半天' } as const

function productView(product: MallProductDetail): Omit<ContentRefView, 'key' | 'type' | 'refId' | 'url'> {
  const rent = product.saleMode === 'RENT'
  const priced = product.offers.filter((offer) => offer.amountMinor !== null)
  const lowest = priced.reduce<(typeof priced)[number] | null>(
    (best, offer) => (best === null || (offer.amountMinor ?? 0) < (best.amountMinor ?? 0) ? offer : best),
    null,
  )
  const several = new Set(priced.map((offer) => offer.amountMinor)).size > 1
  let price: PriceView | null = null
  if (product.saleMode === 'INQUIRY' || !lowest || lowest.amountMinor === null) {
    price = { amount: '询价', unit: '' }
  } else {
    const unit = MALL_UNITS[lowest.unit]
    price = { amount: formatProServiceYuan(lowest.amountMinor), unit: `${unit}${several || rent ? ' 起' : ''}`.trim() }
  }
  return {
    tag: rent ? '租赁' : '商品',
    tagTone: TONES.goods,
    icon: rent ? 'calendar' : 'shop',
    title: product.name,
    price,
    actionLabel: rent ? '看设备' : '看商品',
    primary: false,
    imageUrl: httpsOnly(product.coverImage?.url),
  }
}

function activityPrice(detail: Pick<ActivityDetail, 'price'>): PriceView {
  return { amount: formatActivityPrice(detail.price), unit: isPerPersonPrice(detail.price) ? '/人' : '' }
}

async function resolveOne(ref: ContentRef, cityCode: string, apis: ContentLinkedApis): Promise<ContentRefView> {
  const base = { key: `${ref.type}:${ref.refId}`, type: ref.type, refId: ref.refId, url: contentRefTargetUrl(ref, cityCode) }
  switch (ref.type) {
    case 'INSPECTION_SERVICE': {
      const service = await apis.inspection.loadDetail(ref.refId, cityCode)
      return {
        ...base,
        tag: '服务',
        tagTone: TONES.service,
        icon: 'eye',
        title: service.title,
        price: service.priceAmountMinor === null
          ? { amount: '按项目报价', unit: '' }
          : { amount: formatProServiceYuan(service.priceAmountMinor), unit: service.priceUnitLabel },
        actionLabel: '看服务',
        primary: true,
        // The detail falls back to a bundled stand-in picture; only a picture from the server counts.
        imageUrl: httpsOnly(service.heroImageUrl),
      }
    }
    case 'PRO_SERVICE': {
      const service = await apis.proService.loadService(ref.refId, cityCode)
      const price = formatProServicePrice(service.price)
      return {
        ...base,
        tag: '服务',
        tagTone: TONES.service,
        icon: 'gear',
        title: service.title,
        price: { amount: price.amount, unit: price.unit },
        actionLabel: '看服务',
        primary: true,
        imageUrl: httpsOnly(service.coverUrl),
      }
    }
    case 'PRODUCT':
      return { ...base, ...productView(await apis.mall.loadProduct(ref.refId, cityCode)) }
    case 'ACTIVITY': {
      const activity = await apis.activity.loadDetail(ref.refId, cityCode)
      return {
        ...base,
        tag: '活动',
        tagTone: TONES.activity,
        icon: 'flag',
        title: activity.title,
        price: activityPrice(activity),
        actionLabel: activity.enrollmentStatus === 'OPEN' ? '去报名' : '看活动',
        primary: false,
        imageUrl: httpsOnly(activity.coverImage?.url),
      }
    }
    case 'VENUE': {
      const venue = await apis.experience.loadVenue(ref.refId, cityCode)
      const prices = venue.projects.map((project) => project.priceAmountMinor)
      const lowest = prices.length ? Math.min(...prices) : null
      return {
        ...base,
        tag: '场馆',
        tagTone: TONES.venue,
        icon: 'location',
        title: venue.name,
        price: lowest === null
          ? null
          : { amount: formatVenuePrice(lowest), unit: lowest > 0 && new Set(prices).size > 1 ? '起' : '' },
        actionLabel: '看场馆',
        primary: false,
        imageUrl: httpsOnly(venue.coverImage?.url),
      }
    }
  }
}

/**
 * 内容中提到: every mention read from its own module in the article's city, in the article's order.
 * One that cannot be read (taken down, not sold in this city, or the request failed) is left out.
 */
export async function resolveContentRefs(
  refs: readonly ContentRef[],
  cityCode: string,
  apis: ContentLinkedApis,
): Promise<ContentRefView[]> {
  const results = await Promise.allSettled(refs.map((ref) => resolveOne(ref, cityCode, apis)))
  return results.flatMap((result) => (result.status === 'fulfilled' ? [withPreviewPicture(result.value)] : []))
}

// Preview builds draw the prototype's picture where the module gives none.
function withPreviewPicture(view: ContentRefView): ContentRefView {
  if (view.imageUrl) return view
  const cover = resolveRefPreviewCover(view.type, view.refId)
  return cover ? { ...view, imageUrl: cover.url, imageContain: cover.contain, imagePadded: cover.padded ?? false } : view
}

// ---- The topic page's 本季活动 and 到馆体验 ----

export interface TopicActivityView {
  activityId: string
  tag: string
  tagTone: Tone
  title: string
  when: string
  price: PriceView
  status: string
  statusTone: 'ok' | 'warn' | 'off'
  imageUrl: string | null
  url: string
}

const ACTIVITY_TAG_TONES: Record<ActivityDetail['type'], Tone> = {
  CONTEST: TONES.activity,
  STUDY_TOUR: TONES.activity,
  OPEN_DAY: TONES.venue,
  TRAINING: TONES.service,
}

export function toTopicActivityView(activity: ActivityDetail, cityCode: string): TopicActivityView {
  const next = activity.sessions.find((session) => session.status !== 'ENDED' && session.status !== 'CLOSED')
    ?? activity.sessions.find((session) => session.status !== 'ENDED')
    ?? null
  const when = [
    activity.scheduleNote,
    next ? formatActivityShortDateTime(next.startsAt) : null,
    activity.location.name,
  ].filter((part): part is string => Boolean(part?.trim())).join(' · ')
  const open = activity.enrollmentStatus === 'OPEN' && next?.status === 'OPEN'
  return {
    activityId: activity.activityId,
    tag: ACTIVITY_TYPE_LABELS[activity.type],
    tagTone: ACTIVITY_TAG_TONES[activity.type],
    title: activity.title,
    when,
    price: activityPrice(activity),
    status: open && next ? `余 ${next.seatsLeft}/${next.capacity}` : ACTIVITY_STATUS_LABELS[activity.enrollmentStatus],
    statusTone: activity.enrollmentStatus === 'OPEN'
      ? 'ok'
      : activity.enrollmentStatus === 'CLOSED' || activity.enrollmentStatus === 'ENDED' ? 'off' : 'warn',
    imageUrl: httpsOnly(activity.coverImage?.url) ?? resolveTopicActivityPreviewCover(activity.activityId),
    url: buildActivityDetailUrl(activity.activityId, cityCode),
  }
}

/** 本季活动: the topic's activities read by id, in the topic's order; one that cannot be read is left out. */
export async function loadTopicActivities(
  activityIds: readonly string[],
  cityCode: string,
  apis: Pick<ContentLinkedApis, 'activity'>,
): Promise<TopicActivityView[]> {
  const results = await Promise.allSettled(activityIds.map((id) => apis.activity.loadDetail(id, cityCode)))
  return results.flatMap((result) => (result.status === 'fulfilled' ? [toTopicActivityView(result.value, cityCode)] : []))
}

export interface TopicVenueSessionView {
  key: string
  projectName: string
  // "今天 14:00 场"
  when: string
  // "余 6 位" or "已满"
  seats: string
  full: boolean
}

export interface TopicVenueView {
  venueId: string
  name: string
  openingNote: string | null
  url: string
  sessions: TopicVenueSessionView[]
}

export const TOPIC_VENUE_SESSION_LIMIT = 3

function toSessionView(projectName: string, session: VenueSession): TopicVenueSessionView {
  const full = session.status === 'FULL' || session.seatsLeft <= 0
  return {
    key: session.sessionId,
    projectName,
    when: `今天 ${formatVenueSessionTime(session)} 场`,
    seats: full ? '已满' : `余 ${session.seatsLeft} 位`,
    full,
  }
}

/**
 * 到馆体验: each venue of the topic with today's sessions that have not ended, earliest first, a few
 * of them. Sessions of a project that cannot be read are left out; a venue that cannot be read is too.
 */
export async function loadTopicVenues(
  venueIds: readonly string[],
  cityCode: string,
  apis: Pick<ContentLinkedApis, 'experience'>,
  now: Date = new Date(),
): Promise<TopicVenueView[]> {
  const date = toBeijingDate(now)
  const venues = await Promise.allSettled(venueIds.map(async (venueId): Promise<TopicVenueView> => {
    const venue = await apis.experience.loadVenue(venueId, cityCode)
    const perProject = await Promise.allSettled(venue.projects.map(async (project) => {
      const sessions = await apis.experience.loadSessions({ venueId, cityCode, projectId: project.projectId, date })
      return sessions
        .filter((session) => session.status !== 'ENDED')
        .map((session) => ({ startsAt: session.startsAt, view: toSessionView(project.name, session) }))
    }))
    const sessions = perProject
      .flatMap((result) => (result.status === 'fulfilled' ? result.value : []))
      .sort((left, right) => Date.parse(left.startsAt) - Date.parse(right.startsAt))
      .slice(0, TOPIC_VENUE_SESSION_LIMIT)
      .map((item) => item.view)
    return {
      venueId: venue.venueId,
      name: venue.name,
      openingNote: venue.openingNote?.trim() || null,
      url: buildVenueDetailUrl(venue.venueId, cityCode),
      sessions,
    }
  }))
  return venues.flatMap((result) => (result.status === 'fulfilled' ? [result.value] : []))
}

// ---- The topic page's 商家内容 (1.3.0) ----

/** One card of 商家内容 (board Topic): the merchant's product as the mall sells it in this city now. */
export interface TopicMerchantView {
  productId: string
  // "示例机器人商家" — shown as "示例机器人商家发布" next to 广告.
  merchantName: string
  title: string
  // "¥1,299"; "询价" when nobody quotes a price.
  amount: string
  // "起 · 3 家在售"
  meta: string
  imageUrl: string | null
  url: string
}

export function toTopicMerchantView(
  item: ContentTopicMerchantItem,
  product: MallProductDetail,
  cityCode: string,
): TopicMerchantView | null {
  const sellers = new Set(product.offers.map((offer) => offer.merchantId)).size
  // Nobody sells it here any more: the card is left out.
  if (sellers === 0) return null
  const priced = product.offers.filter((offer) => offer.amountMinor !== null)
  const lowest = priced.reduce<(typeof priced)[number] | null>(
    (best, offer) => (best === null || (offer.amountMinor ?? 0) < (best.amountMinor ?? 0) ? offer : best),
    null,
  )
  const several = new Set(priced.map((offer) => offer.amountMinor)).size > 1
  const sold = `${sellers} 家在售`
  let amount = '询价'
  let meta = sold
  if (product.saleMode !== 'INQUIRY' && lowest && lowest.amountMinor !== null) {
    amount = formatProServiceYuan(lowest.amountMinor)
    const unit = `${MALL_UNITS[lowest.unit]}${several || product.saleMode === 'RENT' ? ' 起' : ''}`.trim()
    meta = unit ? `${unit} · ${sold}` : sold
  }
  return {
    productId: product.productId,
    merchantName: item.merchant.name,
    title: product.name,
    amount,
    meta,
    imageUrl: httpsOnly(product.coverImage?.url) ?? resolveTopicMerchantPreview(product.productId),
    url: buildMallProductUrl(product.productId, cityCode),
  }
}

/**
 * 商家内容: each product read from the mall in the topic's city, in the topic's order. One that cannot
 * be read (taken down, not sold in this city, the request failed) is left out.
 */
export async function loadTopicMerchantItems(
  items: readonly ContentTopicMerchantItem[],
  cityCode: string,
  apis: Pick<ContentLinkedApis, 'mall'>,
): Promise<TopicMerchantView[]> {
  const results = await Promise.allSettled(items.map((item) => apis.mall.loadProduct(item.productId, cityCode)))
  return results.flatMap((result, index) => {
    const item = items[index]
    if (result.status !== 'fulfilled' || !item) return []
    const view = toTopicMerchantView(item, result.value, cityCode)
    return view ? [view] : []
  })
}
