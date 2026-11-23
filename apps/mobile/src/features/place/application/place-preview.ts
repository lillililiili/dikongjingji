// Whether the shared picker pages of plan 08 S5-10 (boards MapPick, RegionPick, PhotoViewer, Scan,
// CityClosed) may be shown. Public builds keep what they had (the system location picker, no scan
// button, the plain home page).
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch PLACES (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const PLACE_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_PLACES__
