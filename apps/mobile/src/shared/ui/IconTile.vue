<script setup lang="ts">
import { computed } from 'vue'

import LineIcon from './LineIcon.vue'
import { ICON_TILE_TONES, type IconTileTone } from './icon-tile-tones'
import type { LineIconName } from './line-icons'

// The boards' icon tile (light-theme.css `.theme-icon-tile`): a rounded square tilted -3°,
// a white 1px edge, a coloured drop shadow and a small white highlight in the top right.
// The icon inside is turned back upright. Tones follow the board palette order.
const props = withDefaults(
  defineProps<{
    tone?: IconTileTone
    name?: LineIconName
    d?: string
    size?: number
    iconSize?: number
    /** Some boards use their own tile colours; these override the tone. */
    bg?: string
    ink?: string
  }>(),
  { tone: 'blue', name: undefined, d: undefined, size: 44, iconSize: 24, bg: undefined, ink: undefined },
)

const palette = computed(() => ICON_TILE_TONES[props.tone])
const style = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`,
  background: props.bg ?? palette.value.bg,
  boxShadow: `0 5px 10px ${palette.value.shadow}`,
}))
</script>

<template>
  <view class="icon-tile" :style="style">
    <view class="icon-tile__icon">
      <slot>
        <LineIcon :name="name" :d="d" :size="iconSize" :color="ink ?? palette.ink" />
      </slot>
    </view>
    <view class="icon-tile__shine" />
  </view>
</template>

<style scoped>
.icon-tile {
  position: relative;
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.85);
  border-radius: 16px;
  transform: rotate(-3deg);
  box-sizing: border-box;
}

.icon-tile__icon {
  display: flex;
  transform: rotate(3deg);
}

.icon-tile__shine {
  position: absolute;
  top: 5px;
  right: 5px;
  width: 7px;
  height: 7px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.72);
  pointer-events: none;
}
</style>
