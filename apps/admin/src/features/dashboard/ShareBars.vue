<script setup lang="ts">
/** 横向占比条：可选排行序号徽标，名称在左、进度条居中、数值在右。 */
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
  ranked?: boolean
}>()

function width(value: number): string {
  const max = Math.max(0, ...props.rows.map((row) => row.value))
  if (max <= 0 || value <= 0) return '0%'
  return `${Math.max(3, (value / max) * 100)}%`
}
</script>

<template>
  <p v-if="rows.length === 0" class="empty">{{ emptyText }}</p>
  <ul v-else class="bars">
    <li v-for="(row, index) in rows" :key="row.key" :class="['bars__row', { 'is-ranked': ranked }]">
      <span v-if="ranked" :class="['bars__rank', `is-top-${index + 1}`]">{{ index + 1 }}</span>
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
  gap: 11px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.bars__row {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr) 136px;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.bars__row.is-ranked {
  grid-template-columns: 22px 84px minmax(0, 1fr) 136px;
}

.bars__rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 5px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 11.5px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.bars__rank.is-top-1 {
  background: #e6f4ff;
  color: #0958d9;
}

.bars__rank.is-top-2 {
  background: #f0f5ff;
  color: #2f54eb;
}

.bars__rank.is-top-3 {
  background: #f5f5f5;
  color: #595959;
}

.bars__label {
  overflow: hidden;
  font-weight: 400;
  color: var(--admin-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bars__track {
  height: 10px;
  border-radius: 2px;
  background: #f0f0f0;
  overflow: hidden;
}

.bars__fill {
  display: block;
  height: 100%;
  border-radius: 2px;
  background: var(--el-color-primary);
}

.bars__value {
  text-align: right;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--admin-heading);
}

.bars__value small {
  margin-left: 6px;
  font-weight: 400;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.empty {
  margin: 0;
  padding: 28px 0;
  text-align: center;
  color: var(--admin-text-secondary);
  font-size: 13px;
}
</style>
