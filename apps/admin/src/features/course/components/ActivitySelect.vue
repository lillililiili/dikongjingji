<script setup lang="ts">
/**
 * 线下班挂靠的活动：按名称远程搜运营端活动列表（活动与场次的接口）。v-model 是活动 ID；已选的活动
 * 读一次详情带出名称（读不到时显示 ID，说明活动可能已删除）。
 */
import { computed, onMounted, ref, watch } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import {
  ACTIVITY_STATUS_OPTIONS,
  ACTIVITY_TYPE_OPTIONS,
  createActivityApi,
  labelOf,
  type OpsActivityStatus,
  type ActivityType,
} from '@/features/activity/activity-api'

const props = defineProps<{
  modelValue: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const api = createActivityApi(http)
const options = ref<Array<{ value: string, label: string }>>([])
const known = ref<Record<string, string>>({})
const loading = ref(false)
const failure = ref('')

function describe(activity: { title: string, type: ActivityType, cityName: string | null, cityCode: string, status: OpsActivityStatus }): string {
  return `${activity.title}（${labelOf(ACTIVITY_TYPE_OPTIONS, activity.type)} · ${activity.cityName ?? activity.cityCode} · `
    + `${labelOf(ACTIVITY_STATUS_OPTIONS, activity.status)}）`
}

const shown = computed(() => {
  const list = [...options.value]
  if (props.modelValue && !list.some((option) => option.value === props.modelValue)) {
    list.unshift({ value: props.modelValue, label: known.value[props.modelValue] ?? props.modelValue })
  }
  return list
})

async function search(keyword: string): Promise<void> {
  loading.value = true
  failure.value = ''
  try {
    const page = await api.list({ keyword, cityCode: '', type: '', status: '' }, 1, 20)
    options.value = page.items.map((item) => ({ value: item.activityId, label: describe(item) }))
  } catch (error) {
    failure.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

async function resolve(id: string): Promise<void> {
  if (!id || known.value[id] || options.value.some((option) => option.value === id)) return
  try {
    const activity = await api.detail(id)
    known.value = { ...known.value, [id]: describe(activity) }
  } catch {
    known.value = { ...known.value, [id]: `读不到这个活动（${id.slice(0, 8)}…），可能已删除` }
  }
}

watch(() => props.modelValue, (id) => {
  void resolve(id)
})

onMounted(() => {
  void resolve(props.modelValue)
})
</script>

<template>
  <div class="course-picker">
    <el-select
      :model-value="modelValue || undefined"
      filterable
      remote
      :remote-method="search"
      :loading="loading"
      :disabled="disabled"
      placeholder="输入活动名称搜索"
      style="width: 420px"
      @update:model-value="(value: string | undefined) => emit('update:modelValue', value ?? '')"
      @visible-change="(open: boolean) => open && options.length === 0 && search('')"
    >
      <el-option v-for="option in shown" :key="option.value" :label="option.label" :value="option.value" />
    </el-select>
    <span v-if="failure" class="course-picker__error">{{ failure }}</span>
  </div>
</template>

<style scoped>
.course-picker {
  display: flex;
  align-items: center;
  gap: 8px;
}

.course-picker__error {
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
