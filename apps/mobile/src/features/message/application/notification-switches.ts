import type { NotificationExtrasApi } from '../data/notification-extras-api'
import type { NotificationPreferences, NotificationSwitch } from '../domain/notification-preferences'

export interface NotificationSwitchesOptions {
  api: Pick<NotificationExtrasApi, 'readPreferences' | 'updatePreferences'>
  getAccessToken(): string | null
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
  let queue: Promise<void> = Promise.resolve()
  let revision = 0

  return {
    async load(): Promise<void> {
      const token = options.getAccessToken()
      const requested = ++revision
      if (!token) {
        options.set(null)
        return
      }
      try {
        const preferences = await options.api.readPreferences(token)
        if (requested === revision) options.set(preferences)
      } catch {
        // The group stays hidden rather than showing switches that may be wrong.
      }
    },

    toggle(key: NotificationSwitch): Promise<void> {
      const current = options.get()
      const token = options.getAccessToken()
      if (!current || !token) return queue
      const value = !current[key]
      options.set({ ...current, [key]: value })
      revision += 1
      queue = queue.then(async () => {
        try {
          const saved = await options.api.updatePreferences(token, { [key]: value })
          const latest = options.get()
          // Keep later taps that are still on their way; take the server's answer for this switch.
          if (latest) options.set({ ...latest, [key]: saved[key] })
        } catch {
          const latest = options.get()
          if (latest) options.set({ ...latest, [key]: !value })
          options.onSaveFailed?.()
        }
      })
      return queue
    },
  }
}
