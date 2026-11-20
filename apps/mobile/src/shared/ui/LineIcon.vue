<script setup lang="ts">
import { computed } from 'vue'

import { lineIconDataUri, lineIconPath, type LineIconName } from './line-icons'

// A board line icon. Pass `name` for a listed icon or `d` for a path copied from a board.
// `fill` switches to a solid shape (the boards' city triangle, for example).
const props = withDefaults(
  defineProps<{
    name?: LineIconName
    d?: string
    size?: number
    color?: string
    strokeWidth?: number
    fill?: string
  }>(),
  { name: undefined, d: undefined, size: 24, color: '#202c42', strokeWidth: 1.8, fill: undefined },
)

const source = computed(() =>
  lineIconDataUri({
    d: props.d ?? (props.name ? lineIconPath(props.name) : ''),
    color: props.color,
    strokeWidth: props.strokeWidth,
    fill: props.fill,
  }),
)
</script>

<template>
  <image
    class="line-icon"
    :src="source"
    mode="aspectFit"
    :style="{ width: `${size}px`, height: `${size}px` }"
    aria-hidden="true"
  />
</template>

<style scoped>
.line-icon {
  display: block;
  flex: none;
}
</style>
