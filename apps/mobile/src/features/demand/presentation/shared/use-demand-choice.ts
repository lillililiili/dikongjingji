import { ref } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'

import { describeDemandChoiceFailure } from '../../application/demand-view'
import { demandApi } from '../../data/demand-api-client'
import type { DemandQuote } from '../../domain/demand'

interface DemandChoiceOptions {
  getAccessToken(): string | null
  // The version the quotes on screen were read with (the quotes list's demandVersion).
  getDemandVersion(): number | null
  getDemandId(): string | null
  onLogin(): void
  // Read the demand and its quotes again; after a choice or a stale one.
  reload(): Promise<void>
  // A line for the page to show (the server's answer in words).
  notify(message: string): void
  // The choice made a service order (S4-3): the page goes to it. Absent where service orders are
  // not open, and then the choice stays on this page.
  openServiceOrder?(serviceOrderId: string): void
}

/**
 * 选择此报价 on the quotes and compare pages: ask first, then send the quote and its version with
 * the demand version as If-Match. A choice the server refuses because something changed reads
 * everything again and says what happened; nothing is marked chosen until the server says so.
 */
export function useDemandChoice(options: DemandChoiceOptions) {
  const asking = ref<DemandQuote | null>(null)
  const busy = ref(false)

  function ask(quote: DemandQuote): void {
    if (!quote.choosable || busy.value) return
    asking.value = quote
  }

  function keep(): void {
    if (!busy.value) asking.value = null
  }

  async function confirm(): Promise<void> {
    const quote = asking.value
    const token = options.getAccessToken()
    const demandId = options.getDemandId()
    const version = options.getDemandVersion()
    if (!quote || busy.value) return
    if (!token) {
      options.onLogin()
      return
    }
    if (!demandId || version === null) return
    busy.value = true
    try {
      const chosen = await demandApi.choose(token, demandId, version, quote.quoteId, quote.version)
      asking.value = null
      await options.reload()
      if (chosen.serviceOrderId && options.openServiceOrder) {
        options.openServiceOrder(chosen.serviceOrderId)
        return
      }
      options.notify(`已选定 ${quote.provider.name} 的报价`)
    } catch (error) {
      if (error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')) {
        options.onLogin()
        return
      }
      const failure = describeDemandChoiceFailure(error instanceof ApiRequestError
        ? { status: error.status, code: error.code, message: error.message }
        : null)
      asking.value = null
      if (failure.reload) await options.reload()
      options.notify(failure.message)
    } finally {
      busy.value = false
    }
  }

  return { asking, busy, ask, keep, confirm }
}
