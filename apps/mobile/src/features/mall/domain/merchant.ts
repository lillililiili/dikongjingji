// 商家主页，对应 contracts/openapi-mall-merchant.yaml 1.0.0（S5-2，画板 Merchant 的商家帧）。公开只读。
// 没有资料的行不显示，页面不编数据。

import type { MallImage, MallMerchantKind } from './mall'

// 审核通过、对外展示的商家资料。
export interface MallMerchantProfile {
  businessEntity: string
  intro: string | null
  saleScopeNote: string | null
  // 授权（合作）有效期，YYYY-MM-DD。
  authorizationUntil: string | null
  categoryNote: string | null
  invoiceIssuer: string | null
  aftersaleParty: string | null
  approvedAt: string
}

export type MallMerchantActivityPriceMode = 'PER_PERSON' | 'FREE' | 'INQUIRY'

export interface MallMerchantActivity {
  activityId: string
  title: string
  coverImage: MallImage | null
  venueName: string
  // 最近一场还没开始的场次。
  startsAt: string
  priceMode: MallMerchantActivityPriceMode
  priceAmountMinor: number | null
}

export type MallMerchantContentKind = 'CASE' | 'KNOWLEDGE' | 'VIDEO'

export interface MallMerchantContent {
  // 图文是 articleId，视频是 videoId。
  contentId: string
  kind: MallMerchantContentKind
  title: string
  coverImage: MallImage | null
  sponsored: boolean
  sample: boolean
  publishedAt: string
}

export interface MallMerchantPage {
  merchantId: string
  name: string
  kind: MallMerchantKind | null
  qualificationReviewed: boolean
  profile: MallMerchantProfile | null
  saleCityCodes: string[]
  nationwide: boolean
  productCount: number
  activityCount: number
  activities: MallMerchantActivity[]
  contentCount: number
  contents: MallMerchantContent[]
}
