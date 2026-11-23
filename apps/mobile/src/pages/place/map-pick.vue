<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { mapPlaceProvider } from '@/features/place/data/map-place-provider-client'
import type { PickedPlace } from '@/features/place/domain/map-place'
import { parseMapPickTarget, type MapPickTarget } from '@/features/place/navigation/place-routes'
import MapPickPage from '@/features/place/presentation/MapPickPage.vue'
import { useMapPickResultStore } from '@/features/place/state/map-pick-result'
import { PLACE_PREVIEW_ENABLED } from '@/features/place/application/place-preview'

const results = useMapPickResultStore()
const target = ref<MapPickTarget | null>(null)
const loaded = ref(false)

onLoad((query) => {
  target.value = parseMapPickTarget(query)
  loaded.value = true
})

function goBack(): void {
  uni.navigateBack()
}

// The opening form takes the place when it is shown again (board MapPick: 确认地址 → back).
function confirm(place: PickedPlace): void {
  if (target.value) results.put(target.value.requestId, place)
  uni.navigateBack()
}
</script>

<template>
  <MapPickPage
    v-if="PLACE_PREVIEW_ENABLED && loaded && target"
    :provider="mapPlaceProvider"
    :purpose="target.purpose"
    :city="{ code: target.cityCode, name: target.cityName }"
    @back="goBack"
    @confirm="confirm"
  />
  <view v-else-if="loaded" class="map-pick-missing" data-testid="map-pick-missing">
    <text>地图选点暂不可用，请返回手动填写地址</text>
  </view>
</template>

<style scoped>
.map-pick-missing {
  display: flex;
  min-height: 100vh;
  align-items: center;
  justify-content: center;
  color: #738096;
  background: #f5f7fc;
  font-size: 14px;
}
</style>
