// Whether the shared picker pages of plan 08 S5-10 (boards MapPick, RegionPick, PhotoViewer, Scan,
// CityClosed) may be shown: preview builds only. Public builds keep what they had (the system
// location picker, the address book's own region list, no scan button, the plain home page).
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts; place-routes.test.ts keeps the
// expressions identical.
export const PLACE_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
