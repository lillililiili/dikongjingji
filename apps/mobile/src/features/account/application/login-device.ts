import type { DevicePlatform, LoginDevice } from '../domain/identity-account'

// What the App tells the server about this device when it signs in (登录设备, S5-8): a random key
// kept on the device, a readable name and the platform, and the current city. No hardware ids.
const DEVICE_KEY = 'account.deviceKey'

function randomKey(): string {
  const bytes = new Uint8Array(16)
  if (typeof crypto !== 'undefined' && crypto.getRandomValues) crypto.getRandomValues(bytes)
  else for (let index = 0; index < bytes.length; index++) bytes[index] = Math.floor(Math.random() * 256)
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export function deviceKey(): string {
  try {
    const stored = uni.getStorageSync?.(DEVICE_KEY)
    if (typeof stored === 'string' && /^[A-Za-z0-9-]{8,64}$/.test(stored)) return stored
    const created = randomKey()
    uni.setStorageSync?.(DEVICE_KEY, created)
    return created
  } catch {
    return randomKey()
  }
}

export function platformOf(raw: string | undefined, uniPlatform: string | undefined): DevicePlatform {
  if (uniPlatform === 'web' || uniPlatform === 'h5') return 'H5'
  if (uniPlatform === 'mp-weixin') return 'MP_WEIXIN'
  const value = (raw ?? '').toLowerCase()
  if (value === 'android') return 'ANDROID'
  if (value === 'ios') return 'IOS'
  return 'OTHER'
}

/** iPhone 15, Android 手机, 网页浏览器 — the server falls back to the platform when this is empty. */
export function deviceNameOf(platform: DevicePlatform, model: string | undefined): string | null {
  const name = (model ?? '').trim()
  if (platform === 'H5') return '网页浏览器'
  if (name) return name.slice(0, 40)
  return null
}

export function currentLoginDevice(cityCode: string | null): LoginDevice {
  let platform: DevicePlatform = 'OTHER'
  let model: string | undefined
  try {
    const info = uni.getSystemInfoSync?.() as { platform?: string; uniPlatform?: string; model?: string } | undefined
    platform = platformOf(info?.platform, info?.uniPlatform)
    model = info?.model
  } catch {
    // Keep OTHER.
  }
  return { deviceKey: deviceKey(), deviceName: deviceNameOf(platform, model), platform, cityCode }
}
