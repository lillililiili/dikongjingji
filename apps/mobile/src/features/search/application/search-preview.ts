// Whether the global search (plan 08 S5-1: boards SearchPre, MallSearch and OrderSearch) may be
// shown. Public builds keep the home search box on the inspection catalog.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch SEARCH (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const SEARCH_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_SEARCH__
