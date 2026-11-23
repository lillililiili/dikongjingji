// 首次启动 (board Onboarding): the privacy consent is asked once before the App is used. It is kept
// on the device; the server is not told (nobody is signed in yet).
const CONSENT_KEY = 'account.privacyConsentAt'

export function hasPrivacyConsent(): boolean {
  try {
    const value = uni.getStorageSync?.(CONSENT_KEY)
    return typeof value === 'string' && value.length > 0
  } catch {
    return false
  }
}

export function recordPrivacyConsent(now = new Date()): void {
  try {
    uni.setStorageSync?.(CONSENT_KEY, now.toISOString())
  } catch {
    // Asked again next time.
  }
}

/**
 * Whether the first-launch page opens on its own. Only on a built preview (or a public build once the
 * ACCOUNT switch is on) and only once; the dev server never does, so existing end-to-end runs start on
 * the home page. Settings → 新手引导 opens it any time.
 */
export function shouldShowOnboarding(enabled: boolean, devServer: boolean, consented: boolean): boolean {
  return enabled && !devServer && !consented
}

/** The home page's check: this build, this device. */
export function shouldOpenOnboardingNow(enabled: boolean): boolean {
  return shouldShowOnboarding(enabled, import.meta.env.DEV, hasPrivacyConsent())
}
