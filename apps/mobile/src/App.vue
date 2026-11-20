<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'

import { warmUpInspectionLanEndpoint } from './features/inspection/data/service-flow/inspection-lan-endpoint'

onLaunch(() => {
  warmUpInspectionLanEndpoint()
})
</script>

<style>
page {
  --app-primary-banner-height: 248rpx;
  --app-hero-radius: 22rpx;
  --app-hero-content-top: 26rpx;
  --app-hero-content-left: 32rpx;
  --app-hero-action-height: 46rpx;
  --app-hero-action-padding-inline: 20rpx;
  --app-hero-action-radius: 999px;
  --app-hero-action-font-size: 21rpx;
  --app-hero-action-gap: 10rpx;
  /* 16px, as on every board of the 2026-11-14 prototype. */
  --app-page-horizontal-gutter: 16px;
  --app-root-tabbar-content-offset: calc(76px + env(safe-area-inset-bottom));
  --app-action-orange-start: #d04700;
  --app-action-orange-end: #c53f00;
  --app-secondary-text: #66707c;
  /* Theme layer of the 2026-11-14 boards (light-theme.css). Pages use these instead of
     writing their own colours, so a card or a page head looks the same everywhere. */
  --app-ink: #202c42;
  --app-muted: #738096;
  --app-muted-strong: #627187;
  --app-paper: #f5f7fc;
  --app-orange: #ff6a00;
  --app-orange-ink: #ba470b;
  --app-green: #11804a;
  --app-line: #e5ebf3;
  --app-head-gradient: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
  --app-card-shadow: 0 3px 12px rgba(38, 60, 101, 0.063), inset 0 0 0 1px rgba(232, 237, 244, 0.5);
  --app-primary-shadow: 0 4px 10px rgba(243, 108, 23, 0.16);
  --app-bar-shadow: 0 -4px 18px rgba(38, 59, 98, 0.063);

  min-height: 100%;
  color: #202c42;
  background: #f5f7fc;
  font-family:
    -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC",
    "Microsoft YaHei", sans-serif;
}

/* #ifdef H5 */
html,
body,
uni-scroll-view,
.uni-scroll-view {
  scrollbar-width: none;
}

html::-webkit-scrollbar,
body::-webkit-scrollbar,
uni-scroll-view::-webkit-scrollbar,
.uni-scroll-view::-webkit-scrollbar {
  display: none;
  width: 0;
  height: 0;
}
/* #endif */

button {
  box-sizing: border-box;
}

/* Board card: soft shadow plus a faint 1px edge (light-theme.css `.theme-card`). */
.app-card {
  box-shadow: var(--app-card-shadow);
}

/* Board primary button shadow (light-theme.css `.theme-primary`). */
.app-primary {
  box-shadow: var(--app-primary-shadow);
}
</style>

