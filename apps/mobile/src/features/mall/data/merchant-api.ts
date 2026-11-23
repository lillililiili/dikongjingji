import type { PublicApiHttpClient } from '@/shared/http/public-api-request'

import type { MallImage, MallMerchantKind } from '../domain/mall'
import type {
  MallMerchantActivity,
  MallMerchantActivityPriceMode,
  MallMerchantContent,
  MallMerchantContentKind,
  MallMerchantPage,
  MallMerchantProfile,
} from '../domain/merchant'

interface MediaAssetDto {
  url: string
  alt: string
}

interface MerchantPageDto {
  merchantId: string
  name: string
  kind?: string | null
  qualificationReviewed?: boolean
  profile?: {
    businessEntity: string
    intro?: string | null
    saleScopeNote?: string | null
    authorizationUntil?: string | null
    categoryNote?: string | null
    invoiceIssuer?: string | null
    aftersaleParty?: string | null
    approvedAt: string
  } | null
  saleCityCodes?: string[]
  nationwide?: boolean
  productCount?: number
  activityCount?: number
  activities?: Array<{
    activityId: string
    title: string
    coverImage?: MediaAssetDto | null
    venueName: string
    startsAt: string
    priceMode: string
    priceAmountMinor?: number | null
  }>
  contentCount?: number
  contents?: Array<{
    contentId: string
    kind: string
    title: string
    coverImage?: MediaAssetDto | null
    sponsored?: boolean
    sample?: boolean
    publishedAt: string
  }>
}

export interface MallMerchantApi {
  load(merchantId: string, cityCode: string): Promise<MallMerchantPage>
}

const MERCHANT_KINDS: readonly MallMerchantKind[] = ['FACTORY_DIRECT', 'AUTHORIZED_DEALER', 'LOCAL_STORE']
const PRICE_MODES: readonly MallMerchantActivityPriceMode[] = ['PER_PERSON', 'FREE', 'INQUIRY']
const CONTENT_KINDS: readonly MallMerchantContentKind[] = ['CASE', 'KNOWLEDGE', 'VIDEO']

function oneOf<T extends string>(values: readonly T[], value: unknown): T | null {
  return typeof value === 'string' && (values as readonly string[]).includes(value) ? value as T : null
}

// Only HTTPS pictures reach an <image>, as elsewhere in the mall.
function toImage(asset: MediaAssetDto | null | undefined): MallImage | null {
  return asset && /^https:\/\//i.test(asset.url) ? { url: asset.url, alt: asset.alt } : null
}

const text = (value: string | null | undefined): string | null => {
  const trimmed = value?.trim() ?? ''
  return trimmed === '' ? null : trimmed
}

function toProfile(dto: NonNullable<MerchantPageDto['profile']>): MallMerchantProfile {
  return {
    businessEntity: dto.businessEntity.trim(),
    intro: text(dto.intro),
    saleScopeNote: text(dto.saleScopeNote),
    authorizationUntil: text(dto.authorizationUntil),
    categoryNote: text(dto.categoryNote),
    invoiceIssuer: text(dto.invoiceIssuer),
    aftersaleParty: text(dto.aftersaleParty),
    approvedAt: dto.approvedAt,
  }
}

export function toMallMerchantPage(dto: MerchantPageDto): MallMerchantPage {
  return {
    merchantId: dto.merchantId,
    name: dto.name,
    kind: oneOf(MERCHANT_KINDS, dto.kind),
    // Only an explicit true shows 资质已审核.
    qualificationReviewed: dto.qualificationReviewed === true,
    profile: dto.profile && text(dto.profile.businessEntity) ? toProfile(dto.profile) : null,
    saleCityCodes: (dto.saleCityCodes ?? []).filter((code) => typeof code === 'string' && code !== ''),
    nationwide: dto.nationwide === true,
    productCount: dto.productCount ?? 0,
    activityCount: dto.activityCount ?? 0,
    activities: (dto.activities ?? []).flatMap((activity): MallMerchantActivity[] => {
      const priceMode = oneOf(PRICE_MODES, activity.priceMode)
      return priceMode
        ? [{
          activityId: activity.activityId,
          title: activity.title,
          coverImage: toImage(activity.coverImage),
          venueName: activity.venueName,
          startsAt: activity.startsAt,
          priceMode,
          priceAmountMinor: activity.priceAmountMinor ?? null,
        }]
        : []
    }),
    contentCount: dto.contentCount ?? 0,
    contents: (dto.contents ?? []).flatMap((content): MallMerchantContent[] => {
      const kind = oneOf(CONTENT_KINDS, content.kind)
      return kind
        ? [{
          contentId: content.contentId,
          kind,
          title: content.title,
          coverImage: toImage(content.coverImage),
          sponsored: content.sponsored === true,
          sample: content.sample === true,
          publishedAt: content.publishedAt,
        }]
        : []
    }),
  }
}

export function createMallMerchantApi(http: PublicApiHttpClient): MallMerchantApi {
  return {
    async load(merchantId, cityCode) {
      return toMallMerchantPage(await http.request<MerchantPageDto>({
        method: 'GET',
        url: `/api/v1/mall/merchants/${encodeURIComponent(merchantId)}?cityCode=${encodeURIComponent(cityCode)}`,
      }))
    },
  }
}
