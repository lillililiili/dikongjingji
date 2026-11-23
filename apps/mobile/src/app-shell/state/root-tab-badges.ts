import { defineStore } from 'pinia'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { CART_PREVIEW_ENABLED } from '@/features/mall/application/cart-preview'
import { cartApi } from '@/features/mall/data/cart-api-client'
import { SYSTEM_NOTICE_PREVIEW_ENABLED } from '@/features/message/application/notice-preview'
import { messageApi } from '@/features/message/data/message-api-client'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

// The numbers on the bottom tabs. Only real counts are shown: a tab without one has no badge.
export const useRootTabBadgeStore = defineStore('root-tab-badges', {
  state: () => ({
    messageUnread: 0,
    // Kinds of goods in the cart that can be bought in the current city (prototype board Cart).
    cartCount: 0,
  }),
  actions: {
    setMessageUnread(count: number): void {
      this.messageUnread = Math.max(0, count)
    },
    setCartCount(count: number): void {
      this.cartCount = Math.max(0, count)
    },
  },
})

let refreshRevision = 0

/**
 * Asks the server how many notifications the signed-in account has not read. A guest, a rejected
 * login or a failed request shows no badge rather than an old number.
 */
export async function refreshMessageUnread(): Promise<void> {
  const store = useRootTabBadgeStore()
  const requested = ++refreshRevision
  const token = getBookingAuthContext().getAccessToken()
  if (!token) {
    store.setMessageUnread(0)
    return
  }
  try {
    // A build that shows 系统通知 counts them too (S5-9), the same way the 消息 tab reads them.
    const count = await messageApi.loadUnreadCount(token, {
      systemNotices: SYSTEM_NOTICE_PREVIEW_ENABLED,
      cityCode: useCurrentCityStore().currentCity?.code ?? null,
    })
    if (requested === refreshRevision) store.setMessageUnread(count)
  } catch {
    if (requested === refreshRevision) store.setMessageUnread(0)
  }
}

let cartRevision = 0

/** The cart tab's number. Preview builds only: the public build has no cart yet. */
export async function refreshCartCount(): Promise<void> {
  const store = useRootTabBadgeStore()
  if (!CART_PREVIEW_ENABLED) return
  const requested = ++cartRevision
  const token = getBookingAuthContext().getAccessToken()
  const cityCode = useCurrentCityStore().currentCity?.code
  if (!token || !cityCode) {
    store.setCartCount(0)
    return
  }
  try {
    const count = await cartApi.count(token, cityCode)
    if (requested === cartRevision) store.setCartCount(count)
  } catch {
    if (requested === cartRevision) store.setCartCount(0)
  }
}
