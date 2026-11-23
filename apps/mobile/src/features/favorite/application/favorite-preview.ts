// Whether favorites and the product comparison (plan 08 S5-6) may be shown. Each entry also needs
// its own module's switch (the 收藏 on a detail page only shows where that page's actions show, the
// mall's 对比 only where the mall channel shows).
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch FAVORITE (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const FAVORITE_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_FAVORITE__
