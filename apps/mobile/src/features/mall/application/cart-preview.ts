// Whether the cart, the confirm page and the address book may be shown (preview builds only).
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts. cart-selection.test.ts keeps the
// expressions identical.
export const CART_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
