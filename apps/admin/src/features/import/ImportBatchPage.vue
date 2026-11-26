<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
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
let revision = 0

async function load(): Promise<void> {
  const id = batchId.value
  const request = ++revision
  batch.value = null
  createdAccounts.value = []
  running.value = false
  loading.value = !!id
  error.value = ''
  if (!id) return
  try {
    const [loaded, specs] = await Promise.all([api.batch(id), kinds.value.length ? kinds.value : api.kinds()])
    if (request !== revision) return
    if (loaded.batchId !== id) throw new Error('读取的批次号与当前页面不一致，请重新加载。')
    batch.value = loaded
    kinds.value = specs
  } catch (failure) {
    if (request === revision) error.value = errorMessage(failure)
  } finally {
    if (request === revision) loading.value = false
  }
}

async function run(): Promise<void> {
  const selected = batch.value
  if (!selected || selected.batchId !== batchId.value || running.value || !canRun(selected, writable.value)) return
  const request = revision
  running.value = true
  try {
    const imported = await runImport(api, selected)
    if (request === revision && imported?.batchId === selected.batchId) {
      batch.value = imported
      createdAccounts.value = imported.createdAccounts
    }
  } finally {
    if (request === revision) running.value = false
  }
}

watch(batchId, load, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { revision++ })
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
      <el-result v-if="error" icon="error" :title="error">
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
