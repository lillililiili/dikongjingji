// Whether settings, 协议与规则 and 关于 (step G1) may be shown: preview builds only.
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts. settings-routes.test.ts keeps the
// expressions identical.
export const SETTINGS_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'

/** The version of this build (manifest versionName); empty when the runtime does not say. */
export function readAppVersion(): string {
  try {
    return uni.getAppBaseInfo?.().appVersion?.trim() ?? ''
  } catch {
    return ''
  }
}
