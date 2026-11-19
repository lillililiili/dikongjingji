// Whether the trade pages (cashier, payment result, goods orders) and their entries may be shown.
// Preview builds only, like the cart and the confirm page they continue from.
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts. trade-preview.test.ts keeps the
// expressions identical.
export const TRADE_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
