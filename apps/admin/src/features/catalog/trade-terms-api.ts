import type { ApiClient } from '@/core/http/api-client'
import { charCount } from '@/core/ui/format'

// 契约 contracts/openapi-ops-merchant-profile.yaml 1.0.0 里商品的交易条款（readProductTradeTerms、setProductNoReasonReturn、
// setOfferTradeTerms），只给平台运营。七天无理由默认支持，标“不支持”后七天无理由的售后不能选（质量问题不受影响）；
// 每个供给的开票方、售后责任方留空表示按商家资料，再没有就是商家自己。改动记在“商品与供给”的操作记录里
// （PRODUCT_UPDATE、OFFER_UPDATE）；已经下的订单用下单时的快照，不受影响。没有版本号。

export interface MallOfferTerms {
  offerId: string
  skuName: string
  merchantId: string
  merchantName: string
  /** null 是全国。 */
  cityCode: string | null
  status: 'ACTIVE' | 'DISABLED'
  /** 供给上填的；null 表示按商家资料。 */
  invoiceIssuer: string | null
  aftersaleParty: string | null
  /** 实际生效的（供给 → 商家资料 → 商家名称），商品详情和订单上显示的就是它。 */
  effectiveInvoiceIssuer: string | null
  effectiveAftersaleParty: string | null
}

export interface MallTradeTerms {
  productId: string
  productName: string
  noReasonReturn: boolean
  offers: MallOfferTerms[]
}

export interface OfferTermsDraft {
  invoiceIssuer: string
  aftersaleParty: string
}

export const TERMS_LIMITS = {
  invoiceIssuer: 60,
  aftersaleParty: 60,
} as const

export const OFFER_TERMS_STATUS_LABELS: Record<MallOfferTerms['status'], string> = {
  ACTIVE: '启用',
  DISABLED: '停用',
}

/** 开票方、售后责任方各不超过 60 个字（留空可以）。没问题返回 null。 */
export function offerTermsError(draft: OfferTermsDraft): string | null {
  if (charCount(draft.invoiceIssuer.trim()) > TERMS_LIMITS.invoiceIssuer) {
    return `开票方最多 ${TERMS_LIMITS.invoiceIssuer} 个字`
  }
  if (charCount(draft.aftersaleParty.trim()) > TERMS_LIMITS.aftersaleParty) {
    return `售后责任方最多 ${TERMS_LIMITS.aftersaleParty} 个字`
  }
  return null
}

function blankToNull(value: string): string | null {
  const text = value.trim()
  return text === '' ? null : text
}

/** OfferTermsRequest：去掉首尾空白，留空写 null（= 按商家资料）。 */
export function offerTermsBody(draft: OfferTermsDraft) {
  return { invoiceIssuer: blankToNull(draft.invoiceIssuer), aftersaleParty: blankToNull(draft.aftersaleParty) }
}

/** 输入框的初值：供给上填的（没填是空串）。 */
export function offerTermsDraft(offer: MallOfferTerms): OfferTermsDraft {
  return { invoiceIssuer: offer.invoiceIssuer ?? '', aftersaleParty: offer.aftersaleParty ?? '' }
}

/** 输入框和供给上存的是否不同（决定“保存”按钮能不能点）。 */
export function offerTermsDirty(offer: MallOfferTerms, draft: OfferTermsDraft): boolean {
  const body = offerTermsBody(draft)
  return body.invoiceIssuer !== offer.invoiceIssuer || body.aftersaleParty !== offer.aftersaleParty
}

export function createTradeTermsApi(http: ApiClient) {
  const path = (productId: string) => `/api/v1/ops/catalog/products/${encodeURIComponent(productId)}/trade-terms`
  return {
    read(productId: string): Promise<MallTradeTerms> {
      return http.get<MallTradeTerms>(path(productId))
    },
    setNoReasonReturn(productId: string, noReasonReturn: boolean): Promise<MallTradeTerms> {
      return http.put<MallTradeTerms>(path(productId), { noReasonReturn })
    },
    setOfferTerms(productId: string, offerId: string, draft: OfferTermsDraft): Promise<MallTradeTerms> {
      return http.put<MallTradeTerms>(
        `/api/v1/ops/catalog/products/${encodeURIComponent(productId)}/offers/${encodeURIComponent(offerId)}/trade-terms`,
        offerTermsBody(draft))
    },
  }
}

export type TradeTermsApi = ReturnType<typeof createTradeTermsApi>
