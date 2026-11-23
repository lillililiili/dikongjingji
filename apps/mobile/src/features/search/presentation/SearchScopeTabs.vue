<script setup lang="ts">
import { SEARCH_SCOPES, SEARCH_SCOPE_LABELS, type SearchScope } from '../domain/search'

// The six scope tabs of boards SearchPre and MallSearch: the chosen one larger, bold and underlined.
defineProps<{ scope: SearchScope }>()
const emit = defineEmits<{ select: [scope: SearchScope] }>()
</script>

<template>
  <view class="search-scopes" role="tablist" aria-label="搜索范围">
    <button
      v-for="item in SEARCH_SCOPES"
      :key="item"
      :class="['search-scopes__tab', { 'search-scopes__tab--on': item === scope }]"
      :data-testid="`search-scope-${item.toLowerCase()}`"
      role="tab"
      :aria-selected="item === scope"
      @click="emit('select', item)"
    >
      {{ SEARCH_SCOPE_LABELS[item] }}
      <view v-if="item === scope" class="search-scopes__bar" />
    </button>
  </view>
</template>

<style scoped>
.search-scopes {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  padding: 0 8px;
}

.search-scopes__tab {
  position: relative;
  display: flex;
  width: 100%;
  height: 42px;
  margin: 0;
  padding: 0;
  align-items: center;
  justify-content: center;
  border: 0;
  color: #627187;
  background: transparent;
  font-family: inherit;
  font-size: 15px;
  font-weight: 400;
  line-height: 1.2;
  white-space: nowrap;
}

.search-scopes__tab::after {
  border: 0;
}

.search-scopes__tab--on {
  color: #202c42;
  font-size: 16px;
  font-weight: 700;
}

.search-scopes__bar {
  position: absolute;
  bottom: 4px;
  left: 50%;
  width: 16px;
  height: 3px;
  margin-left: -8px;
  border-radius: 3px;
  background: #ff6a00;
}
</style>
