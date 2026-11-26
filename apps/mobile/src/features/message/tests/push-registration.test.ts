import { describe, expect, it, vi } from 'vitest'

import { createPushRegistration, type PushDevice } from '../application/push-registration'

function setup(device: PushDevice | null, token: string | null = 'token') {
  let city: string | null = '370500'
  const api = {
    registerPushDevice: vi.fn(async () => undefined),
    unregisterPushDevice: vi.fn(async () => undefined),
  }
  const registration = createPushRegistration({
    api,
    getAccessToken: () => token,
    getCityCode: () => city,
    readDevice: async () => device,
  })
  return { api, registration, moveTo: (next: string | null) => { city = next } }
}

const ANDROID: PushDevice = { platform: 'ANDROID', pushClientId: 'cid-1' }

describe('push id report (S5-9)', () => {
  it('reports the device once per account, device and city', async () => {
    const { api, registration, moveTo } = setup(ANDROID)

    expect(await registration.report()).toBe(true)
    expect(await registration.report()).toBe(false)
    moveTo('370100')
    expect(await registration.report()).toBe(true)

    expect(api.registerPushDevice.mock.calls).toEqual([
      ['token', { platform: 'ANDROID', pushClientId: 'cid-1', cityCode: '370500' }],
      ['token', { platform: 'ANDROID', pushClientId: 'cid-1', cityCode: '370100' }],
    ])
  })

  it('reports nothing on H5 or without a login, and retries after a failure', async () => {
    const h5 = setup(null)
    expect(await h5.registration.report()).toBe(false)
    const guest = setup(ANDROID, null)
    expect(await guest.registration.report()).toBe(false)
    expect(h5.api.registerPushDevice).not.toHaveBeenCalled()
    expect(guest.api.registerPushDevice).not.toHaveBeenCalled()

    const flaky = setup(ANDROID)
    flaky.api.registerPushDevice.mockRejectedValueOnce(new Error('offline'))
    expect(await flaky.registration.report()).toBe(false)
    expect(await flaky.registration.report()).toBe(true)
  })

  it('revokes the device before signing out and reports it again afterwards', async () => {
    const { api, registration } = setup(ANDROID)
    await registration.report()

    await registration.revoke('token')
    expect(api.unregisterPushDevice).toHaveBeenCalledWith('token', ANDROID)
    expect(await registration.report()).toBe(true)

    api.unregisterPushDevice.mockRejectedValueOnce(new Error('offline'))
    await expect(registration.revoke('token')).resolves.toBeUndefined()
  })

  it('drops an old registration and unregisters only its captured owner when the device id arrives late', async () => {
    let token = 'a'
    let finish!: (device: PushDevice) => void
    const device = new Promise<PushDevice>(resolve => { finish = resolve })
    const api = { registerPushDevice: vi.fn(), unregisterPushDevice: vi.fn() }
    const registration = createPushRegistration({
      api, getAccessToken: () => token, getCityCode: () => null, readDevice: () => device,
    })
    const reporting = registration.report()
    const revoking = registration.revoke(token)
    token = 'b'
    finish(ANDROID)
    await expect(reporting).resolves.toBe(false)
    await revoking
    expect(api.registerPushDevice).not.toHaveBeenCalled()
    expect(api.unregisterPushDevice).toHaveBeenCalledWith('a', ANDROID)
  })
})
