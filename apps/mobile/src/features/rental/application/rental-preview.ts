// Whether equipment rental (F3) may be shown: preview builds only.
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts: importing the shared flag from a
// page in another chunk can move the flag out of the home chunk and leave the home page's sample
// content in the public bundle. rental-routes.test.ts keeps the expressions identical.
export const RENTAL_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
