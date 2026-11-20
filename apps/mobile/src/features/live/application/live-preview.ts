// Whether the live channel, the live bar, the preview page and the live room (M20) may be shown:
// preview builds only. Public builds keep 发现 as it is and the live pages show a closed notice.
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts. live-routes.test.ts keeps the
// expressions identical.
export const LIVE_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
