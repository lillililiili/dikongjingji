<script setup lang="ts">
/** 横向条：一种颜色表示大小，名称在左、数字在右（直接标在每一行上，不靠颜色区分）。 */
export interface ShareRow {
  key: string
  label: string
  value: number
  valueText: string
  note?: string
}

const props = defineProps<{
  rows: ShareRow[]
  emptyText: string
}>()

function width(value: number): string {
  const max = Math.max(0, ...props.rows.map((row) => row.value))
  if (max <= 0 || value <= 0) return '0%'
  return `${Math.max(2, (value / max) * 100)}%`
}
</script>

<template>
  <p v-if="rows.length === 0" class="empty">{{ emptyText }}</p>
  <ul v-else class="bars">
    <li v-for="row in rows" :key="row.key" class="bars__row">
      <span class="bars__label" :title="row.label">{{ row.label }}</span>
      <span class="bars__track"><span class="bars__fill" :style="{ width: width(row.value) }" /></span>
      <span class="bars__value">
        {{ row.valueText }}<small v-if="row.note">{{ row.note }}</small>
      </span>
    </li>
  </ul>
</template>

<style scoped>
.bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.bars__row {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr) 136px;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.bars__label {
  overflow: hidden;
  color: var(--admin-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bars__track {
  height: 12px;
  border-radius: 4px;
  background: var(--admin-surface-muted);
  overflow: hidden;
}

.bars__fill {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--el-color-primary);
}

.bars__value {
  text-align: right;
  font-variant-numeric: tabular-nums;
  color: var(--admin-text);
}

.bars__value small {
  margin-left: 6px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.empty {
  margin: 0;
  padding: 24px 0;
  text-align: center;
  color: var(--admin-text-secondary);
  font-size: 13px;
}
</style>
