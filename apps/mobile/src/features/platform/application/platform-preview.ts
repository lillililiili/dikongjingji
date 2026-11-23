// Whether this is a preview build (VITE_PLATFORM_PREVIEW_ENABLED=true) or the dev server: the home
// page's sample content and its preview-only blocks follow it. The single features have their own
// release switches (src/app-shell/release/feature-flags.ts); the vite define turns
// __RELEASE_PREVIEW_BUILD__ into a literal.
export const PLATFORM_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__
