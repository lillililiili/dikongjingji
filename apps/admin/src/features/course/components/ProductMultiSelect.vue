<script setup lang="ts">
/**
 * 附带这门课的商城商品（最多 max 个，只存商品 ID）：按名称远程搜运营端商品列表（商品与供给的接口）。
 * 已选的商品各读一次详情带出名称；读不到时显示 ID。
 */
import { computed, onMounted, ref, watch } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { categoryLabel, createCatalogApi, productStatus, type OpsProductSummary } from '@/features/catalog/catalog-api'

const props = defineProps<{
  modelValue: string[]
  max: number
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
}>()

const api = createCatalogApi(http)
const options = ref<Array<{ value: string, label: string }>>([])
const known = ref<Record<string, string>>({})
const loading = ref(false)
const failure = ref('')

function describe(product: Pick<OpsProductSummary, 'name' | 'category' | 'status'>): string {
  return `${product.name}（${categoryLabel(product.category)} · ${productStatus(product.status).label}）`
}

const shown = computed(() => {
  const list = [...options.value]
  for (const id of props.modelValue) {
    if (!list.some((option) => option.value === id)) list.unshift({ value: id, label: known.value[id] ?? id })
  }
  return list
})

async function search(keyword: string): Promise<void> {
  loading.value = true
  failure.value = ''
  try {
    const page = await api.list({ keyword, category: '', saleMode: '', status: '' }, 1, 20)
    options.value = page.items.map((item) => ({ value: item.productId, label: describe(item) }))
    known.value = { ...known.value, ...Object.fromEntries(options.value.map((option) => [option.value, option.label])) }
  } catch (error) {
    failure.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

async function resolve(ids: readonly string[]): Promise<void> {
  const unknown = ids.filter((id) => !known.value[id])
  await Promise.all(unknown.map(async (id) => {
    try {
      const detail = await api.detail(id)
      known.value = { ...known.value, [id]: describe(detail.product) }
    } catch {
      known.value = { ...known.value, [id]: `读不到这个商品（${id.slice(0, 8)}…）` }
    }
  }))
}

watch(() => props.modelValue, (ids) => {
  void resolve(ids)
})

onMounted(() => {
  void resolve(props.modelValue)
})
</script>

<template>
  <div class="course-products">
    <el-select
      :model-value="modelValue"
      multiple
      filterable
      remote
      :remote-method="search"
      :loading="loading"
      :disabled="disabled"
      :multiple-limit="max"
      placeholder="输入商品名称搜索"
      style="width: 100%"
      @update:model-value="(value: string[]) => emit('update:modelValue', value)"
      @visible-change="(open: boolean) => open && options.length === 0 && search('')"
    >
      <el-option v-for="option in shown" :key="option.value" :label="option.label" :value="option.value" />
    </el-select>
    <span class="course-products__hint">
      {{ modelValue.length }} / {{ max }} 个；课程随这些商城商品附带，只存商品 ID。
      <template v-if="failure"><span class="course-products__error">{{ failure }}</span></template>
    </span>
  </div>
</template>

<style scoped>
.course-products {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.course-products__hint {
  font-size: 12px;
  line-height: 18px;
  color: var(--admin-text-secondary);
}

.course-products__error {
  margin-left: 8px;
  color: var(--el-color-danger);
}
</style>
