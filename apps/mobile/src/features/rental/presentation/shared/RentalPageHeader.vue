<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

// The bar of every rental page (RentalInquiry, RentalQuote, OrderRental, Contract boards): back, a
// left-aligned title and, on the quote page, who the inquiry is recorded under.
defineProps<{
  title: string
  badge?: string | null
}>()

const emit = defineEmits<{
  back: []
}>()
</script>

<template>
  <view class="rental-header">
    <button class="rental-header__back" data-testid="rental-back" aria-label="返回" @click="emit('back')">
      <UniIcons type="left" size="22" color="#202c42" />
    </button>
    <text class="rental-header__title">{{ title }}</text>
    <text v-if="badge" class="rental-header__badge" data-testid="rental-header-badge">{{ badge }}</text>
  </view>
</template>

<style scoped>
.rental-header {
  display: flex;
  min-height: calc(60px + var(--status-bar-height));
  padding: var(--status-bar-height) 8px 0;
  align-items: center;
  gap: 4px;
  background: linear-gradient(125deg, #fff9f3, #f3f7ff 65%, #edf9f4);
  box-sizing: border-box;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.rental-header__back {
  display: flex;
  width: 44px;
  height: 44px;
  flex: none;
  align-items: center;
  justify-content: center;
}

.rental-header__title {
  overflow: hidden;
  color: #202c42;
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rental-header__badge {
  overflow: hidden;
  min-width: 0;
  margin-right: 8px;
  margin-left: auto;
  padding: 3px 8px;
  border-radius: 6px;
  color: #ba470b;
  background: #fff0e3;
  font-size: 12px;
  line-height: 17px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
