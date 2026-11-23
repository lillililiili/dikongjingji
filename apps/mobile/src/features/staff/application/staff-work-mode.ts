// Whether the App's 工作模式 (核销工作台, S3-3) may be shown: it verifies activity tickets and venue
// codes, so it opens wherever either of those features is open (dev server and preview builds now;
// a public build once the ACTIVITY or EXPERIENCE switch is turned on, docs/ops/发布开关.md).
//
// Reads the build constants itself instead of importing the two module flags; activity-preview.ts
// tells why. It is not a release switch of its own, so it is not named *_PREVIEW_ENABLED.
export const STAFF_WORK_MODE_ENABLED =
  import.meta.env.DEV || __RELEASE_FEATURE_ACTIVITY__ || __RELEASE_FEATURE_EXPERIENCE__
