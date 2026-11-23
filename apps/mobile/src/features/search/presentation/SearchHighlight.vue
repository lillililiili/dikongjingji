<script setup lang="ts">
import type { HighlightParts } from '../application/search-view'

// One line with the matched words in orange (boards SearchPre, MallSearch, OrderSearch).
defineProps<{ parts: HighlightParts }>()
</script>

<template>
  <text class="search-highlight"><text>{{ parts.pre }}</text><text class="search-highlight__hit">{{ parts.hit }}</text><text>{{ parts.post }}</text></text>
</template>

<style scoped>
.search-highlight__hit {
  color: #ff6a00;
  /* The matched words stay on one line (board OrderSearch). */
  white-space: nowrap;
}
</style>
