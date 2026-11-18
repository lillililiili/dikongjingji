import { defineStore } from 'pinia'

import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { messageApi } from '@/features/message/data/message-api-client'

// The numbers on the bottom tabs. Only real counts are shown: a tab without one has no badge.
export const useRootTabBadgeStore = defineStore('root-tab-badges', {
  state: () => ({
    messageUnread: 0,
  }),
  actions: {
    setMessageUnread(count: number): void {
      this.messageUnread = Math.max(0, count)
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
    const count = await messageApi.loadUnreadCount(token)
    if (requested === refreshRevision) store.setMessageUnread(count)
  } catch {
    if (requested === refreshRevision) store.setMessageUnread(0)
  }
}
