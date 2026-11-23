// Whether customer-service conversations, settings, help and the shared pages of step G1 may be
// shown: preview builds only. Public builds keep each feature's own typed gate.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch SUPPORT (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const SUPPORT_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_SUPPORT__
