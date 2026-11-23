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
})
