import type { NotificationExtrasApi } from '../data/notification-extras-api'
import type { NotificationPreferences, NotificationSwitch } from '../domain/notification-preferences'

export interface NotificationSwitchesOptions {
  api: Pick<NotificationExtrasApi, 'readPreferences' | 'updatePreferences'>
  getAccessToken(): string | null
  getAccountKey?(): string | null
  get(): NotificationPreferences | null
  set(next: NotificationPreferences | null): void
  /** Told when a change could not be saved and the switch went back. */
  onSaveFailed?(): void
}

/**
 * The 通知 switches of 设置 (S5-9). A tap shows at once and is then saved; only the tapped switch is
 * sent. Taps made while one is being saved wait their turn, so the server sees them in order. A failed
 * save puts the switch back as it was.
 */
export function createNotificationSwitches(options: NotificationSwitchesOptions) {
  const account = options.getAccountKey ?? options.getAccessToken
  const newSession = (owner: string | null) => ({
    owner, revision: 0, queue: Promise.resolve(), lastTap: new Map<NotificationSwitch, number>(),
    confirmed: null as NotificationPreferences | null,
  })
  let active = newSession(null)
  function session() {
    const owner = account()
    if (active.owner !== owner) {
      active = newSession(owner)
      options.set(null)
    }
    return active
  }
  const current = (s: typeof active) => active === s && s.owner === account()

  return {
    async load(): Promise<void> {
      const s = session()
      const requested = ++s.revision
      if (!s.owner) {
        options.set(null)
        return
      }
      try {
        await s.queue
        const token = options.getAccessToken()
        if (!token || requested !== s.revision || !current(s)) return
        const preferences = await options.api.readPreferences(token)
        if (requested === s.revision && current(s)) {
          s.confirmed = preferences
          options.set(preferences)
        }
      } catch {
        // The group stays hidden rather than showing switches that may be wrong.
      }
    },

    toggle(key: NotificationSwitch): Promise<void> {
      const s = session()
      const displayed = options.get()
      if (!displayed || !s.confirmed || !s.owner) return s.queue
      const value = !displayed[key]
      options.set({ ...displayed, [key]: value })
      const request = ++s.revision
      s.lastTap.set(key, request)
      s.queue = s.queue.then(async () => {
        const token = options.getAccessToken()
        if (!token || !current(s)) return
        try {
          const saved = await options.api.updatePreferences(token, { [key]: value })
          if (current(s) && s.confirmed) s.confirmed = { ...s.confirmed, [key]: saved[key] }
        } catch {
          if (current(s) && s.lastTap.get(key) === request) options.onSaveFailed?.()
        }
        const latest = options.get()
        if (s.confirmed && latest && current(s) && s.lastTap.get(key) === request) {
          options.set({ ...latest, [key]: s.confirmed[key] })
          s.lastTap.delete(key)
        }
      })
      return s.queue
    },
  }
}
