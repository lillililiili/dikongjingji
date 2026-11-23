// Whether the trade pages (cashier, payment result, goods orders) and their entries may be shown.
// Preview builds only, like the cart and the confirm page they continue from.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch TRADE (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const TRADE_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_TRADE__
