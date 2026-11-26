<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { mapPlaceProvider } from '@/features/place/data/map-place-provider-client'
import type { PickedPlace } from '@/features/place/domain/map-place'
import { leavePlacePage, parseMapPickTarget, type MapPickTarget } from '@/features/place/navigation/place-routes'
import MapPickPage from '@/features/place/presentation/MapPickPage.vue'
import PlaceUnavailable from '@/features/place/presentation/PlaceUnavailable.vue'
import { useMapPickResultStore } from '@/features/place/state/map-pick-result'
import { PLACE_PREVIEW_ENABLED } from '@/features/place/application/place-preview'

const results = useMapPickResultStore()
const target = ref<MapPickTarget | null>(null)
const loaded = ref(false)

onLoad((query) => {
  target.value = parseMapPickTarget(query)
  loaded.value = true
})

// The opening form takes the place when it is shown again (board MapPick: 确认地址 → back).
function confirm(place: PickedPlace): void {
  if (target.value) results.put(target.value.requestId, place)
  leavePlacePage()
}
</script>

<template>
  <MapPickPage
    v-if="PLACE_PREVIEW_ENABLED && loaded && target"
    :provider="mapPlaceProvider"
    :purpose="target.purpose"
    :city="{ code: target.cityCode, name: target.cityName }"
    @back="leavePlacePage"
    @confirm="confirm"
  />
  <PlaceUnavailable v-else-if="loaded" message="地图选点暂不可用，可在表单中手动填写地址" data-testid="map-pick-missing" />
</template>
