// Whether the live channel, the live bar, the preview page and the live room (M20) may be shown:
// preview builds only. Public builds keep 发现 as it is and the live pages show a closed notice.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch LIVE (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const LIVE_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_LIVE__
