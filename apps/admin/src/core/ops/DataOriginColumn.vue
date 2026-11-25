<script setup lang="ts" generic="T extends object">
import { computed, ref, watch } from 'vue'
import { http } from '@/core/http'

const props = defineProps<{ resource: string; items: T[]; rowKey: string }>()
type Origin = 'DEVELOPMENT_SEED' | 'RECORDED_CREATION' | 'UNVERIFIED'
type Evidence = { id: string; origin: Origin; hasRecordedChanges: boolean }
const labels: Record<Origin, string> = {
  DEVELOPMENT_SEED: '预置示例', RECORDED_CREATION: '业务录入', UNVERIFIED: '来源待核实',
}
const evidence = ref<Record<string, Evidence>>({})
const failed = ref(false)
const retry = ref(0)
const pageIds = computed(() => props.items.map(row => String(row[props.rowKey as keyof T])))
function originLabel(id: string) {
  const item = evidence.value[id]
  return item ? labels[item.origin] : '—'
}

watch([pageIds, () => props.resource, retry], async ([ids, resource], _, onCleanup) => {
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  evidence.value = {}
  failed.value = false
  if (!ids.length) return
  try {
    const result = await http.get<Evidence[]>('/api/v1/ops/data-origins/' + resource,
      { ids: ids.join(',') }, controller.signal)
    if (!controller.signal.aborted) evidence.value = Object.fromEntries(result.map(item => [item.id, item]))
  } catch {
    if (!controller.signal.aborted) failed.value = true
  }
}, { immediate: true })
</script>

<template>
  <el-table-column label="数据来源" min-width="135">
    <template #default="{ row }">
      <template v-if="evidence[row[rowKey]]">
        <span>{{ originLabel(row[rowKey]) }}</span>
        <small v-if="evidence[row[rowKey]]?.hasRecordedChanges" class="changes">有变更记录</small>
      </template>
      <el-button v-else-if="failed" link type="primary" @click="retry++">读取失败，重试</el-button>
      <span v-else>—</span>
    </template>
  </el-table-column>
</template>

<style scoped>
.changes { display: block; color: var(--el-text-color-secondary); }
</style>
