// Whether favorites and the product comparison (plan 08 S5-6) may be shown: preview builds only.
// Each entry also needs its own module's switch (the 收藏 on a detail page only shows where that
// page's actions show, the mall's 对比 only where the mall channel shows).
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts. favorite-routes.test.ts keeps the
// expressions identical.
export const FAVORITE_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
