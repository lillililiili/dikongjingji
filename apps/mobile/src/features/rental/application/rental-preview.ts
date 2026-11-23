// Whether equipment rental (F3) may be shown: preview builds only.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch RENTAL (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const RENTAL_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_RENTAL__
