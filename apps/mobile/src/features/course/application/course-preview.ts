// Whether the course pages (S5-5) may be shown: the mall's 课程 tab, course detail and buying,
// trial, learning and 我的课程. Preview builds only; public builds keep the tab's empty state and
// the pages show a closed notice.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch COURSE (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on.
export const COURSE_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_COURSE__
