// Whether entries that are not built yet (cart, buy now, inquiry) may be shown.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch MALL (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const MALL_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_MALL__
