// Whether 系统通知、通知详情、设置里的通知开关、协议页的“有更新”和推送标识上报 (S5-9) may be shown.
//
// Reads its own compile-time constant instead of importing a shared flag; activity-preview.ts tells why.
// Release switch SYSTEM_NOTICE (src/app-shell/release/release-features.json, docs/ops/发布开关.md): open on
// the dev server and preview builds, and on public builds once the switch is turned on. While it is off
// the 消息 tab never asks the server for announcements. The 通知 switches live on 设置, so they also need
// SETTINGS.
export const SYSTEM_NOTICE_PREVIEW_ENABLED = import.meta.env.DEV || __RELEASE_FEATURE_SYSTEM_NOTICE__
