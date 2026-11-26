<script>
import destinationMap from './destination-map-options'
export default destinationMap
</script>

<template>
  <view class="destination-map" :map-input="mapInput" :change:map-input="destinationMapRenderer.draw" />
</template>

<!-- Render in the real Android WebView/H5 DOM, outside the native AMap GL surface. -->
<script module="destinationMapRenderer" lang="renderjs">
import renderer from './map-frame-renderer'
export default renderer
</script>
<style scoped>
.destination-map { width: 100%; height: 100%; }
</style>
