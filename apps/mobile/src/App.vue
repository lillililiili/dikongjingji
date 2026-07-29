<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app'

import { warmUpInspectionLanEndpoint } from './features/inspection/data/service-flow/inspection-lan-endpoint'

onLaunch(() => {
  warmUpInspectionLanEndpoint()
})
</script>

<style>
page {
  min-height: 100%;
  color: #191919;
  background: #f7f8fa;
  font-family:
    -apple-system, BlinkMacSystemFont, "PingFang SC", "Noto Sans CJK SC",
    "Microsoft YaHei", sans-serif;
}

button {
  box-sizing: border-box;
}
</style>

