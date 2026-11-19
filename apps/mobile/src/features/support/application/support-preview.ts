// Whether customer-service conversations, settings, help and the shared pages of step G1 may be
// shown: preview builds only. Public builds keep each feature's own typed gate.
//
// Repeats the expression of PLATFORM_PREVIEW_ENABLED instead of importing it, for the reason
// written in features/activity/application/activity-preview.ts. support-routes.test.ts keeps the
// expressions identical.
export const SUPPORT_PREVIEW_ENABLED = import.meta.env.DEV || import.meta.env.VITE_PLATFORM_PREVIEW_ENABLED === 'true'
