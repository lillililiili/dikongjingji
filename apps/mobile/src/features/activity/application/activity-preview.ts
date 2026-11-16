// Whether entries that are not built yet (enrol, waitlist, team sign-up, consult) may be shown.
//
// This repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, on purpose.
// The public bundle only loses the home page's sample content because the shared flag is bundled
// into the same chunk as the home page, where the minifier can fold it. Importing that flag from
// this page (a separate chunk that also shares the activity modules with the home page) made the
// bundler move the flag into a common chunk, and the sample content stayed in the public bundle
// (scripts/verify-public-production-preview.mjs fails on it). activity-preview.test.ts keeps the
// two expressions identical.
export const ACTIVITY_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
