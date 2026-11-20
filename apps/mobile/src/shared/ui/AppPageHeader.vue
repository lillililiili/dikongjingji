<script setup lang="ts">
import LineIcon from './LineIcon.vue'

// The light second-level page head of the 2026-11-14 boards (light-theme.css `.theme-page-head`):
// the soft three-colour gradient, a 44px back button, an 18px/700 title (left or centred), an
// optional 12px subtitle, a right-hand slot for page actions and a bottom slot for tabs.
withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    centered?: boolean
    /** Plain white instead of the gradient, for the few boards that draw a white head. */
    plain?: boolean
    hideBack?: boolean
    testId: string
  }>(),
  { subtitle: undefined, centered: false, plain: false, hideBack: false },
)

const emit = defineEmits<{ back: [] }>()
</script>

<template>
  <view :class="['app-head', { 'app-head--plain': plain }]" :data-testid="`${testId}-head`">
    <view :class="['app-head__bar', { 'app-head__bar--centered': centered }]">
      <button
        v-if="!hideBack"
        class="app-head__back"
        :data-testid="`${testId}-back`"
        aria-label="返回"
        @click="emit('back')"
      >
        <LineIcon name="back" :size="24" color="#202c42" />
      </button>
      <view v-else class="app-head__gap" />
      <view class="app-head__titles">
        <text class="app-head__title">{{ title }}</text>
        <text v-if="subtitle" class="app-head__subtitle">{{ subtitle }}</text>
      </view>
      <view class="app-head__actions">
        <slot name="actions">
          <view v-if="centered" class="app-head__spacer" />
        </slot>
      </view>
    </view>
    <slot name="bottom" />
  </view>
</template>

<style scoped>
.app-head {
  padding-top: var(--status-bar-height);
  color: #202c42;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
}

.app-head--plain {
  background: #fff;
}

.app-head__bar {
  display: flex;
  min-height: 60px;
  padding: 0 8px;
  align-items: center;
  gap: 4px;
  box-sizing: border-box;
}

.app-head__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  margin: 0;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.app-head__back::after {
  border: 0;
}

.app-head__gap {
  width: 8px;
  flex: none;
}

.app-head__titles {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
}

.app-head__title {
  overflow: hidden;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-head__subtitle {
  overflow: hidden;
  color: #738096;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-head__bar--centered .app-head__titles {
  align-items: center;
  text-align: center;
}

.app-head__actions {
  display: flex;
  flex: none;
  align-items: center;
}

.app-head__spacer {
  width: 44px;
  height: 44px;
}
</style>
