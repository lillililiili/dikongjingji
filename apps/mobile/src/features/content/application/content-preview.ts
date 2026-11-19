// Whether the discover page's articles and topics (step H1) may be shown: preview builds only.
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts. content-routes.test.ts keeps the
// expressions identical.
export const CONTENT_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
