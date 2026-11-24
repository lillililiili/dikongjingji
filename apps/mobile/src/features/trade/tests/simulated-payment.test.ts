import { describe, expect, it, vi } from 'vitest'

import { SIMULATED_PAYMENT_ENABLED, createSimulatedPaymentLauncher } from '../application/simulated-payment'
import source from '../application/simulated-payment.ts?raw'
import { createTradeApi } from '../data/trade-api'

const APP_CALL = {} as never

describe('simulated payment (demo builds only)', () => {
  it('stays off unless the build asks for it, and never on public builds', () => {
    expect(SIMULATED_PAYMENT_ENABLED).toBe(false)
    expect(source).toContain("(import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__) && import.meta.env.VITE_SIMULATED_PAYMENT === 'true'")
  })

  it('pays only after the buyer confirms; a refusal or a failure is never reported as paid', async () => {
    const run = (confirmed: boolean, pay: () => Promise<void>) =>
      createSimulatedPaymentLauncher({ amountText: '¥2,040', confirm: async () => confirmed, pay }).launchWechatApp(APP_CALL)
    const pay = vi.fn(async () => undefined)
    expect(await run(false, pay)).toBe('CANCELLED')
    expect(pay).not.toHaveBeenCalled()
    expect(await run(true, pay)).toBe('LAUNCHED')
    expect(await run(true, () => Promise.reject(new Error('down')))).toBe('FAILED')
  })

  it('asks the dev-only endpoint with the buyer token', async () => {
    const request = vi.fn(async () => undefined)
    await createTradeApi(request as never).simulatePayment('tok', 'o-1')
    expect(request).toHaveBeenCalledWith({
      url: '/api/v1/dev/trade/orders/o-1/simulated-payment', method: 'POST', headers: { Authorization: 'Bearer tok' },
    })
  })
})
