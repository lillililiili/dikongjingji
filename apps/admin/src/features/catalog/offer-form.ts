import { minorToYuan, yuanToMinor } from '@/core/ui/money'

import {
  priceAllowed,
  priceUnitsFor,
  type MallMerchantKind,
  type MallPriceUnit,
  type MallSaleMode,
  type OfferDraft,
  type OpsOffer,
} from './catalog-api'

/** 供给弹窗的表单：价格和运费按元输入。 */
export interface OfferForm {
  skuId: string
  merchantId: string
  merchantKind: MallMerchantKind
  cityCode: string
  priceYuan: number | null
  priceUnit: MallPriceUnit
  stockQuantity: number | null
  expressAvailable: boolean
  shippingFeeYuan: number | null
  pickupPoint: string
  deliveryNote: string
  shippingNote: string
  fulfilledFrom: string
}

export function emptyOfferForm(saleMode: MallSaleMode, skuId = ''): OfferForm {
  return {
    skuId,
    merchantId: '',
    merchantKind: 'FACTORY_DIRECT',
    cityCode: '',
    priceYuan: null,
    priceUnit: priceUnitsFor(saleMode)[0] ?? 'ITEM',
    stockQuantity: null,
    expressAvailable: true,
    shippingFeeYuan: 0,
    pickupPoint: '',
    deliveryNote: '',
    shippingNote: '',
    fulfilledFrom: '',
  }
}

export function offerFormFrom(offer: OpsOffer): OfferForm {
  return {
    skuId: offer.skuId,
    merchantId: offer.merchantId,
    merchantKind: offer.merchantKind,
    cityCode: offer.cityCode ?? '',
    priceYuan: minorToYuan(offer.priceAmountMinor),
    priceUnit: offer.priceUnit,
    stockQuantity: offer.stockQuantity,
    expressAvailable: offer.expressAvailable,
    shippingFeeYuan: minorToYuan(offer.shippingFeeMinor),
    pickupPoint: offer.pickupPoint ?? '',
    deliveryNote: offer.deliveryNote ?? '',
    shippingNote: offer.shippingNote ?? '',
    fulfilledFrom: offer.fulfilledFrom ?? '',
  }
}

/** 表单 → 请求（元换成分）。询价商品一律不带价格。 */
export function toOfferDraft(form: OfferForm, saleMode: MallSaleMode): OfferDraft {
  return {
    merchantKind: form.merchantKind,
    cityCode: form.cityCode.trim() === '' ? null : form.cityCode.trim(),
    priceAmountMinor: priceAllowed(saleMode) ? yuanToMinor(form.priceYuan) : null,
    priceUnit: form.priceUnit,
    stockQuantity: form.stockQuantity === null || form.stockQuantity === undefined ? null : form.stockQuantity,
    expressAvailable: form.expressAvailable,
    shippingFeeMinor: yuanToMinor(form.shippingFeeYuan) ?? 0,
    pickupPoint: form.pickupPoint,
    deliveryNote: form.deliveryNote,
    shippingNote: form.shippingNote,
    fulfilledFrom: form.fulfilledFrom,
  }
}

/** 列表里的库存：null 不限、0 缺货、其余“N 件”。 */
export function stockLabel(stock: number | null): string {
  if (stock === null) return '不限'
  if (stock === 0) return '0 件（缺货）'
  return `${stock} 件`
}
