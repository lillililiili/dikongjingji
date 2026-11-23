// Whether the account pages of S5-7 and S5-8 may be shown: phone-number login, changing the number,
// signed-in devices, closing the account, the personal-data export, the permission page, the
// first-launch page, enterprise organizations and the payer row of the cashier. Preview builds only.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch ACCOUNT (src/app-shell/release/release-features.json, docs/ops/发布开关.md): it stays
// closed on public builds until a real SMS provider is connected, so those keep the password login.
export const ACCOUNT_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_ACCOUNT__
