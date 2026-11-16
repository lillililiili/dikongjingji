<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

defineProps<{
  title: string
}>()

const emit = defineEmits<{
  back: []
}>()
</script>

<template>
  <header class="play-header">
    <button
      class="play-header__back"
      data-testid="play-back"
      aria-label="返回"
      @click="emit('back')"
    >
      <UniIcons type="left" size="24" color="#435369" />
    </button>
    <text class="play-header__title">{{ title }}</text>
  </header>
</template>

<style scoped>
.play-header {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  min-height: calc(50px + var(--status-bar-height));
  padding: var(--status-bar-height) 12px 0;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid #edf0f6;
  background: #ffffff;
  box-sizing: border-box;
}

.play-header__back {
  display: flex;
  width: 44px;
  height: 44px;
  margin: 0;
  padding: 0;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
}

.play-header__back::after {
  border: 0;
}

.play-header__title {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: #202c42;
  font-size: 18px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
