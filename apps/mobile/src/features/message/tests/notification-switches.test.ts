import { describe, expect, it, vi } from 'vitest'

import { createNotificationSwitches } from '../application/notification-switches'
import { DEFAULT_NOTIFICATION_PREFERENCES, type NotificationPreferences } from '../domain/notification-preferences'

function setup(token: string | null = 'token') {
  let state: NotificationPreferences | null = null
  const api = {
    readPreferences: vi.fn(async () => ({ ...DEFAULT_NOTIFICATION_PREFERENCES })),
    updatePreferences: vi.fn(async (_token: string, change: Partial<NotificationPreferences>) => ({
      ...DEFAULT_NOTIFICATION_PREFERENCES,
      ...change,
    })),
  }
  const onSaveFailed = vi.fn()
  const switches = createNotificationSwitches({
    api,
    getAccessToken: () => token,
    get: () => state,
    set: (next) => { state = next },
    onSaveFailed,
  })
  return { api, switches, onSaveFailed, state: () => state }
}

describe('通知 switches (S5-9)', () => {
  it('restores the last confirmed value after two quick taps both fail', async () => {
    const { api, switches, state } = setup()
    await switches.load()
    api.updatePreferences.mockRejectedValue(new Error('offline'))
    await Promise.all([switches.toggle('activityPush'), switches.toggle('activityPush')])
    expect(state()?.activityPush).toBe(DEFAULT_NOTIFICATION_PREFERENCES.activityPush)
  })

  it('reads the switches and sends only the tapped one', async () => {
    const { api, switches, state } = setup()
    await switches.load()
    expect(state()).toEqual(DEFAULT_NOTIFICATION_PREFERENCES)

    const saving = switches.toggle('activityPush')
    // Shown at once.
    expect(state()?.activityPush).toBe(false)
    await saving

    expect(api.updatePreferences).toHaveBeenCalledWith('token', { activityPush: false })
    expect(state()).toEqual({ orderServicePush: true, activityPush: false, discoverDot: true })
  })

  it('saves quick taps in order and puts a failed one back', async () => {
    const { api, switches, onSaveFailed, state } = setup()
    await switches.load()
    api.updatePreferences.mockRejectedValueOnce(new Error('offline'))

    const first = switches.toggle('orderServicePush')
    const second = switches.toggle('discoverDot')
    await Promise.all([first, second])

    expect(api.updatePreferences.mock.calls.map((call) => call[1])).toEqual([
      { orderServicePush: false },
      { discoverDot: false },
    ])
    expect(onSaveFailed).toHaveBeenCalledTimes(1)
    expect(state()).toEqual({ orderServicePush: true, activityPush: true, discoverDot: false })
  })

  it('does nothing without a login or before the switches are read', async () => {
    const guest = setup(null)
    await guest.switches.load()
    await guest.switches.toggle('activityPush')
    expect(guest.state()).toBeNull()
    expect(guest.api.readPreferences).not.toHaveBeenCalled()

    const unread = setup()
    await unread.switches.toggle('activityPush')
    expect(unread.api.updatePreferences).not.toHaveBeenCalled()
  })

  it('loads a different account immediately and discards the previous session even after switching back', async () => {
    let owner = 'A'
    let displayed: NotificationPreferences | null = null
    let finish!: (value: NotificationPreferences) => void
    const oldSave = new Promise<NotificationPreferences>(resolve => { finish = resolve })
    const updatePreferences = vi.fn(() => oldSave)
    const switches = createNotificationSwitches({
      getAccessToken: () => owner + '-token',
      getAccountKey: () => owner,
      get: () => displayed,
      set: v => { displayed = v },
      api: { readPreferences: async () => ({ ...DEFAULT_NOTIFICATION_PREFERENCES }), updatePreferences },
    })
    await switches.load()
    const old = switches.toggle('discoverDot')
    const queued = switches.toggle('orderServicePush')
    await Promise.resolve()
    expect(updatePreferences).toHaveBeenCalledTimes(1)
    owner = 'B'
    const read = switches.load()
    expect(displayed).toBeNull()
    await read
    owner = 'A'
    await switches.load()
    finish({ ...DEFAULT_NOTIFICATION_PREFERENCES, discoverDot: false })
    await Promise.all([old, queued])
    expect(updatePreferences).toHaveBeenCalledTimes(1)
    expect(displayed).toEqual(DEFAULT_NOTIFICATION_PREFERENCES)
  })

  it('uses the renewed token for a queued save of the same account', async () => {
    let token = 'first'
    let displayed: NotificationPreferences | null = null
    const api = setup().api
    const switches = createNotificationSwitches({
      api, getAccessToken: () => token, getAccountKey: () => 'same-account',
      get: () => displayed, set: v => { displayed = v },
    })
    await switches.load()
    const saving = switches.toggle('activityPush')
    token = 'renewed'
    await saving
    expect(api.updatePreferences).toHaveBeenCalledWith('renewed', { activityPush: false })
    expect(displayed).toEqual({ ...DEFAULT_NOTIFICATION_PREFERENCES, activityPush: false })
  })
})
