<script setup lang="ts">
import { computed, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import PageSection from '@/core/ui/PageSection.vue'

import CreatedAccountsDialog from './CreatedAccountsDialog.vue'
import ImportBatchResult from './ImportBatchResult.vue'
import {
  canRun,
  createImportApi,
  KIND_LABELS,
  type OpsImportBatch,
  type OpsImportCreatedAccount,
  type OpsImportKindSpec,
} from './import-api'
import { runImport } from './run-import'

const route = useRoute()
const router = useRouter()
const api = createImportApi(http)
const batch = shallowRef<OpsImportBatch | null>(null)
const kinds = shallowRef<OpsImportKindSpec[]>([])
const loading = ref(false)
const running = ref(false)
const error = ref('')
const createdAccounts = shallowRef<OpsImportCreatedAccount[]>([])

const batchId = computed(() => String(route.params.batchId ?? ''))
const writable = computed(() => kinds.value.find((spec) => spec.kind === batch.value?.kind)?.writable ?? false)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [loaded, specs] = await Promise.all([api.batch(batchId.value), kinds.value.length ? kinds.value : api.kinds()])
    batch.value = loaded
    kinds.value = specs
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function run(): Promise<void> {
  if (!batch.value || running.value) return
  running.value = true
  try {
    const imported = await runImport(api, batch.value)
    if (imported) {
      batch.value = imported
      createdAccounts.value = imported.createdAccounts
    }
  } finally {
    running.value = false
  }
}

// 从“内容相同的批次”链接跳到另一个批次时，同一个页面组件要重新读。
watch(batchId, () => {
  void load()
})

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="batch ? `导入批次：${KIND_LABELS[batch.kind]}` : '导入批次'"
    description="批次的文件、问题和改动。初始口令只在导入那一次显示，核对列只在预检时显示，这里都没有。"
    traced
  >
    <template #actions>
      <el-button @click="router.push({ name: 'imports' })">返回批量导入</el-button>
    </template>
    <div v-loading="loading">
      <el-result v-if="error && !batch" icon="error" :title="error">
        <template #extra>
          <el-button type="primary" @click="load">重新加载</el-button>
        </template>
      </el-result>
      <ImportBatchResult
        v-if="batch"
        :batch="batch"
        :runnable="canRun(batch, writable)"
        :running="running"
        :check-only="!writable"
        @run="run"
      />
    </div>
  </PageSection>

  <CreatedAccountsDialog :accounts="createdAccounts" @close="createdAccounts = []" />
</template>
