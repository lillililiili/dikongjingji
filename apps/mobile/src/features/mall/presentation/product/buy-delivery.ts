import type { DeliveryMode, DeliveryOption } from '../../domain/cart'

// What the 规格与数量 sheet shows about delivery: the options of the confirm page's preview of the
// offer, the one it picked and its shipping fee. Null for a guest, who sees the shipping note.
export interface MallBuyDelivery {
  options: DeliveryOption[]
  mode: DeliveryMode | null
  shippingMinor: number
}

export type MallBuyDeliveryLoader = (
  offerId: string,
  quantity: number,
  mode: DeliveryMode | null,
) => Promise<MallBuyDelivery | null>
