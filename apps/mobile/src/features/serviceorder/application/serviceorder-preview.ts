// Whether general service orders (S4-3) may be shown: preview builds only.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch SERVICE_ORDER (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open
// on the dev server and preview builds, and on public builds once the switch is turned on.
export const SERVICE_ORDER_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_SERVICE_ORDER__
