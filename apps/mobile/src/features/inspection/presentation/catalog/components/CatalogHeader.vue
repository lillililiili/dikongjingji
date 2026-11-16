<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'

defineProps<{
  keyword: string
}>()

const emit = defineEmits<{
  back: []
  customerService: []
  'update:keyword': [value: string]
  search: []
  filter: []
}>()

function handleKeywordInput(event: Event): void {
  const value = (
    event as Event & {
      detail?: {
        value?: string
      }
    }
  ).detail?.value

  emit('update:keyword', value?.toString() ?? '')
}
</script>

<template>
  <header class="catalog-header">
    <view class="catalog-header__title-row">
      <button
        class="catalog-header__icon-button"
        data-testid="catalog-back"
        aria-label="返回我要巡检"
        @click="$emit('back')"
      >
        <UniIcons type="left" size="25" color="#1c1f24" />
      </button>
      <text class="catalog-header__title">巡检服务</text>
      <button
        class="catalog-header__service"
        aria-label="联系客服"
        @click="$emit('customerService')"
      >
        <UniIcons type="headphones" size="21" color="#2b2e33" />
        <text>客服</text>
      </button>
    </view>

    <view class="catalog-header__search-row">
      <view class="catalog-search">
        <UniIcons type="search" size="20" color="#8b9198" />
        <input
          class="catalog-search__input"
          data-testid="catalog-search-input"
          type="text"
          confirm-type="search"
          maxlength="50"
          :value="keyword"
          placeholder="搜索巡检服务、场景或关键词"
          placeholder-class="catalog-search__placeholder"
          @input="handleKeywordInput"
          @confirm="$emit('search')"
        />
        <button
          v-if="keyword"
          class="catalog-search__clear"
          aria-label="清空搜索"
          @click="$emit('update:keyword', ''); $emit('search')"
        >
          <UniIcons type="clear" size="18" color="#a3a8af" />
        </button>
      </view>
      <button
        class="catalog-header__filter"
        data-testid="catalog-filter-open"
        aria-label="打开筛选"
        @click="$emit('filter')"
      >
        <UniIcons type="tune" size="18" color="#4c5158" />
        <text>筛选</text>
      </button>
    </view>
  </header>
</template>

<style scoped>
.catalog-header {
  padding:
    var(--status-bar-height)
    clamp(12px, 3.2vw, 18px)
    0;
  border-bottom: 1px solid #eff1f3;
  background: #ffffff;
  box-sizing: border-box;
}

.catalog-header__title-row {
  display: grid;
  min-height: 46px;
  grid-template-columns: 72px minmax(0, 1fr) 72px;
  align-items: center;
}

.catalog-header__icon-button,
.catalog-header__service,
.catalog-search__clear,
.catalog-header__filter {
  padding: 0;
  border: 0;
  background: transparent;
}

.catalog-header__icon-button::after,
.catalog-header__service::after,
.catalog-search__clear::after,
.catalog-header__filter::after {
  border: 0;
}

.catalog-header__icon-button {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: flex-start;
}

.catalog-header__title {
  overflow: hidden;
  color: #1b1e22;
  font-size: 19px;
  font-weight: 750;
  letter-spacing: 0.01em;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.catalog-header__service {
  justify-self: end;
  display: flex;
  min-width: 58px;
  height: 34px;
  padding: 0 10px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 1px solid #e7e9ec;
  border-radius: 18px;
  color: #4e535a;
  font-size: 12px;
  line-height: 1;
}

.catalog-header__search-row {
  display: grid;
  min-height: 42px;
  padding-bottom: 6px;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
}

.catalog-search {
  display: flex;
  min-width: 0;
  height: 34px;
  padding: 0 12px;
  align-items: center;
  gap: 8px;
  border: 1px solid #d9dde2;
  border-radius: 21px;
  background: #ffffff;
  box-sizing: border-box;
}

.catalog-search__input {
  min-width: 0;
  height: 32px;
  flex: 1;
  color: #272a2f;
  font-size: 14px;
}

.catalog-search__placeholder {
  color: #9aa0a8;
}

.catalog-search__clear {
  display: flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
}

.catalog-header__filter {
  display: flex;
  min-width: 62px;
  height: 44px;
  align-items: center;
  justify-content: center;
  gap: 3px;
  color: #454a51;
  font-size: 14px;
  white-space: nowrap;
}
</style>
