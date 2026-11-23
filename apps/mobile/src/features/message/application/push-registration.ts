import type { NotificationExtrasApi, PushPlatform } from '../data/notification-extras-api'

// Reports this device's push id (S5-9) so the server's push port can reach it. The vendor is not chosen:
// the id comes from uni.getPushClientId on the App and is sent as an opaque string; H5 has none and
// reports nothing. Reporting is a convenience: every failure is swallowed.

export interface PushDevice {
  platform: PushPlatform
  pushClientId: string
}

export interface PushRegistrationOptions {
  api: Pick<NotificationExtrasApi, 'registerPushDevice' | 'unregisterPushDevice'>
  getAccessToken(): string | null
  getCityCode(): string | null
  /** null on H5, on a build without the push module, or when the id cannot be read. */
  readDevice(): Promise<PushDevice | null>
}

export function createPushRegistration(options: PushRegistrationOptions) {
  // The same account, device and city are reported once per launch.
  let lastReported: string | null = null

  return {
    async report(): Promise<boolean> {
      const token = options.getAccessToken()
      if (!token) return false
      const device = await options.readDevice().catch(() => null)
      if (!device) return false
      const cityCode = options.getCityCode()
      const key = `${token}|${device.platform}|${device.pushClientId}|${cityCode ?? ''}`
      if (key === lastReported) return false
      try {
        await options.api.registerPushDevice(token, { ...device, cityCode })
        lastReported = key
        return true
      } catch {
        return false
      }
    },

    /** Before signing out: this account's pushes stop coming to this device. */
    async revoke(): Promise<void> {
      const token = options.getAccessToken()
      lastReported = null
      if (!token) return
      const device = await options.readDevice().catch(() => null)
      if (!device) return
      try {
        await options.api.unregisterPushDevice(token, device)
      } catch {
        // The server keeps the device; the next account to sign in here takes it over.
      }
    },
  }
}

/** The App's push id; null on H5 and wherever uni cannot give one. */
export function readUniPushDevice(): Promise<PushDevice | null> {
  return new Promise((resolve) => {
    let info: { uniPlatform?: string; platform?: string } = {}
    try {
      info = uni.getSystemInfoSync()
    } catch {
      resolve(null)
      return
    }
    const platform: PushPlatform | null = info.platform === 'android' ? 'ANDROID' : info.platform === 'ios' ? 'IOS' : null
    if (info.uniPlatform !== 'app' || !platform || typeof uni.getPushClientId !== 'function') {
      resolve(null)
      return
    }
    uni.getPushClientId({
      success: (result) => {
        const id = typeof result?.cid === 'string' ? result.cid.trim() : ''
        resolve(id ? { platform, pushClientId: id } : null)
      },
      fail: () => resolve(null),
    })
  })
}
