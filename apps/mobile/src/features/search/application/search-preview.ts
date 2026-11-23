// Whether the global search (plan 08 S5-1: boards SearchPre and MallSearch) may be shown:
// preview builds only. Public builds keep the home search box on the inspection catalog.
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts; search-routes.test.ts keeps the
// expressions identical.
export const SEARCH_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
