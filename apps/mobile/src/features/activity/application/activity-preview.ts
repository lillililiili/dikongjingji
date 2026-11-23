// Whether entries that are not built yet (enrol, waitlist, team sign-up, consult) may be shown.
//
// Why each module reads its own compile-time constant instead of importing one shared flag: when
// this page imported PLATFORM_PREVIEW_ENABLED from a separate chunk that also shares the activity
// modules with the home page, the bundler moved the flag into a common chunk where the minifier could
// not fold it, and the home page's sample content stayed in the public bundle
// (scripts/verify-public-production-preview.mjs failed on it). A define constant is a literal in
// every file, so it always folds.
// Release switch ACTIVITY (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const ACTIVITY_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_ACTIVITY__
