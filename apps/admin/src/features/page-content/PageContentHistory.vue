<script setup lang="ts">
import { ref, watch } from 'vue'
import { errorMessage } from '@/core/http/api-error'
import { formatDateTime } from '@/core/ui/format'
import { stateLabels, type Change } from './page-content'
const props = defineProps<{ load: () => Promise<Change[]> }>()
const rows = ref<Change[]>([])
const error = ref('')
const busy = ref(false)
let revision = 0
async function refresh() {
  const seq = ++revision
  rows.value = []; error.value = ''; busy.value = true
  try {
    const data = await props.load()
    if (seq === revision) rows.value = data
  } catch (e) { if (seq === revision) error.value = errorMessage(e) }
  finally { if (seq === revision) busy.value = false }
}
watch(() => props.load, refresh, {immediate:true})
</script>
<template>
  <el-alert v-if="error" type="error" :title="error" :closable="false"><el-button @click="refresh">重试</el-button></el-alert>
  <el-table v-else v-loading="busy" :data="rows" empty-text="尚无运营改动" size="small">
    <el-table-column prop="version" label="版本" width="70" />
    <el-table-column prop="operatorName" label="操作人" />
    <el-table-column label="保存状态"><template #default="{row}">{{ stateLabels[row.status] }}</template></el-table-column>
    <el-table-column label="保存时间"><template #default="{row}">{{ formatDateTime(row.changedAt) }}</template></el-table-column>
  </el-table>
</template>
