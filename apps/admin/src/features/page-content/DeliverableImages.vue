<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { errorMessage } from '@/core/http/api-error'
import MediaField from '@/core/ops/MediaField.vue'
import { pageContent, type Deliverable } from './page-content'
type ImageRow = Deliverable & { draft: string }
const props = defineProps<{ serviceId: string; version: number; disabled: boolean }>()
const emit = defineEmits<{ saved: [] }>()
const rows = ref<ImageRow[]>([])
const error = ref('')
const saving = ref('')
const loading = ref(false)
let generation = 0
let loadedService = ''

async function load() {
  const seq = ++generation
  const serviceId = props.serviceId
  const previous = new Map(loadedService === serviceId ? rows.value.map(row => [row.code, row]) : [])
  if (loadedService !== serviceId) rows.value = []
  error.value = ''
  loading.value = true
  try {
    const data = await pageContent.deliverables(serviceId)
    if (seq !== generation) return
    loadedService = serviceId
    rows.value = data.map(item => {
      const original = previous.get(item.code)
      const draft = original && original.imageUrl === item.imageUrl ? original.draft : item.imageUrl ?? ''
      return { ...item, draft }
    })
  } catch (e) {
    if (seq === generation) error.value = errorMessage(e)
  } finally {
    if (seq === generation) loading.value = false
  }
}

async function save(row: ImageRow) {
  if (saving.value || loading.value || props.disabled) return
  saving.value = row.code
  error.value = ''
  try {
    await pageContent.saveImage(props.serviceId, row.code, row.draft, props.version)
    ElMessage.success('交付样图已保存')
    emit('saved')
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    saving.value = ''
  }
}
watch(() => [props.serviceId, props.version], load, { immediate: true })
</script>
<template>
  <p v-if="disabled">请先保存服务资料，再修改交付样图。</p>
  <el-alert v-if="error" type="error" :title="error" :closable="false"><el-button @click="load">重试</el-button></el-alert>
  <el-table v-loading="loading" :data="rows" empty-text="尚无交付物" row-key="code">
    <el-table-column label="交付物" prop="title" />
    <el-table-column label="样图" min-width="320"><template #default="{row}"><MediaField v-model="row.draft" :disabled="disabled || !!saving" /></template></el-table-column>
    <el-table-column width="100" label="操作"><template #default="{row}">
      <el-button link type="primary" :disabled="disabled || loading || !!saving || row.draft===(row.imageUrl||'')" @click="save(row as ImageRow)">保存样图</el-button>
    </template></el-table-column>
  </el-table>
</template>
