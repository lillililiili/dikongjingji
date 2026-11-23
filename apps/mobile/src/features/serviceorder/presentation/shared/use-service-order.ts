import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'

import { describeServiceOrderFailure } from '../../application/service-order-view'
import { serviceOrderApi } from '../../data/service-order-api-client'
import type { ServiceOrder } from '../../domain/service-order'

type OrderState =
  | { kind: 'loading' }
  | { kind: 'ready'; order: ServiceOrder }
  | { kind: 'missing' }
  | { kind: 'error' }

interface ServiceOrderPageOptions {
  getOrderId(): string | null
  // Null for a guest.
  getAccessToken(): string | null
  getRefreshKey(): number
  onLogin(): void
}

export function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiRequestError && (error.status === 401 || error.code === 'COMMON_UNAUTHORIZED')
}

/**
 * The order behind each service-order page: read again on every show, and every command answered
 * by the server's order. A command the server refuses because something changed reads the order
 * again and says what happened; nothing is shown as done until the server says so.
 */
export function useServiceOrder(options: ServiceOrderPageOptions) {
  const state = ref<OrderState>({ kind: 'loading' })
  const busy = ref(false)
  // What the server answered when a command did not go through; the page shows it until closed.
  const notice = ref<string | null>(null)
  let requestVersion = 0

  const order = computed(() => (state.value.kind === 'ready' ? state.value.order : null))

  async function load(): Promise<void> {
    const token = options.getAccessToken()
    if (!token) {
      options.onLogin()
      return
    }
    const orderId = options.getOrderId()
    if (!orderId) {
      state.value = { kind: 'missing' }
      return
    }
    const requested = ++requestVersion
    if (state.value.kind !== 'ready') state.value = { kind: 'loading' }
    try {
      const loaded = await serviceOrderApi.read(token, orderId)
      if (requested !== requestVersion) return
      state.value = { kind: 'ready', order: loaded }
    } catch (error) {
      if (requested !== requestVersion) return
      if (isUnauthorized(error)) {
        options.onLogin()
        return
      }
      state.value = {
        kind: error instanceof ApiRequestError && (error.status === 404 || error.status === 400) ? 'missing' : 'error',
      }
    }
  }

  /**
   * Runs one command with the token and the order on screen. The result order replaces the one
   * shown; a failure is told in the notice (reading the order again when it changed). Returns whether
   * the command went through.
   */
  async function run(
    command: (token: string, current: ServiceOrder) => Promise<ServiceOrder | void>,
    done?: string,
  ): Promise<boolean> {
    const token = options.getAccessToken()
    const current = order.value
    if (!token) {
      options.onLogin()
      return false
    }
    if (!current || busy.value) return false
    busy.value = true
    notice.value = null
    try {
      const updated = await command(token, current)
      if (updated) {
        requestVersion += 1
        state.value = { kind: 'ready', order: updated }
      }
      if (done) uni.showToast({ title: done, icon: 'none' })
      return true
    } catch (error) {
      if (isUnauthorized(error)) {
        options.onLogin()
        return false
      }
      const failure = describeServiceOrderFailure(error instanceof ApiRequestError
        ? { status: error.status, code: error.code ?? null, message: error.message }
        : null)
      notice.value = failure.message
      if (failure.reload) await load()
      return false
    } finally {
      busy.value = false
    }
  }

  watch(
    () => [options.getRefreshKey(), options.getAccessToken(), options.getOrderId()],
    () => { void load() },
    { immediate: true },
  )

  return { state, order, busy, notice, load, run }
}
