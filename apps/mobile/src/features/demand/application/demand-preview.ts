// Whether publishing demands and comparing quotes (F1) may be shown: preview builds only.
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts: importing the shared flag from a
// page in another chunk can move the flag out of the home chunk and leave the home page's sample
// content in the public bundle. demand-routes.test.ts keeps the expressions identical.
export const DEMAND_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
