// Whether settings, 协议与规则 and 关于 (step G1) may be shown: preview builds only.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch SETTINGS (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const SETTINGS_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_SETTINGS__

/** The version of this build (manifest versionName); empty when the runtime does not say. */
export function readAppVersion(): string {
  try {
    return uni.getAppBaseInfo?.().appVersion?.trim() ?? ''
  } catch {
    return ''
  }
}
