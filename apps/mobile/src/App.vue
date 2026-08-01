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
  --app-page-horizontal-gutter: 9px;
  --app-root-tabbar-content-offset: calc(76px + env(safe-area-inset-bottom));
  --app-action-orange-start: #d04700;
  --app-action-orange-end: #c53f00;
  --app-secondary-text: #66707c;

  min-height: 100%;
  color: #191919;
  background: #f7f8fa;
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
</style>

