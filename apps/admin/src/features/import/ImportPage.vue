<script setup lang="ts">
import type { UploadFile, UploadUserFile } from 'element-plus'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import CreatedAccountsDialog from './CreatedAccountsDialog.vue'
import ImportBatchResult from './ImportBatchResult.vue'
import {
  canRun,
  createImportApi,
  filesProblem,
  KIND_LABELS,
  KIND_ORDER,
  STATUS_VIEW,
  type BatchFilters,
  type OpsImportBatch,
  type OpsImportBatchSummary,
  type OpsImportCreatedAccount,
  type OpsImportKind,
  type OpsImportKindSpec,
} from './import-api'
import { runImport } from './run-import'

const router = useRouter()
const api = createImportApi(http)
const kinds = shallowRef<OpsImportKindSpec[]>([])
const kindsError = ref('')
const kind = ref<OpsImportKind | ''>('')
const fileList = ref<UploadUserFile[]>([])
const checking = ref(false)
const running = ref(false)
const result = shallowRef<OpsImportBatch | null>(null)
const createdAccounts = shallowRef<OpsImportCreatedAccount[]>([])

const batches = usePagedList<OpsImportBatchSummary, BatchFilters>(
  (filters, page, size) => api.batches(filters, page, size), { kind: '' }, 10)

const chosen = computed(() => kinds.value.find((spec) => spec.kind === kind.value) ?? null)
const resultWritable = computed(() => kinds.value.find((spec) => spec.kind === result.value?.kind)?.writable ?? false)

async function loadKinds(): Promise<void> {
  kindsError.value = ''
  try {
    const loaded = await api.kinds()
    kinds.value = [...loaded].sort((a, b) => KIND_ORDER.indexOf(a.kind) - KIND_ORDER.indexOf(b.kind))
  } catch (failure) {
    kindsError.value = errorMessage(failure)
  }
}

function choose(next: OpsImportKind): void {
  if (kind.value === next) return
  kind.value = next
  fileList.value = []
  result.value = null
}

function rawFiles(): File[] {
  return fileList.value.map((file) => file.raw).filter((raw): raw is NonNullable<UploadFile['raw']> => !!raw)
}

async function check(): Promise<void> {
  if (!kind.value || checking.value) return
  const files = rawFiles()
  const problem = filesProblem(files)
  if (problem) {
    showError(new Error(problem))
    return
  }
  checking.value = true
  try {
    result.value = await api.check(kind.value, files)
    void batches.search()
  } catch (failure) {
    showError(failure)
  } finally {
    checking.value = false
  }
}

async function run(): Promise<void> {
  const batch = result.value
  if (!batch || running.value) return
  running.value = true
  try {
    const imported = await runImport(api, batch)
    if (imported) {
      result.value = imported
      createdAccounts.value = imported.createdAccounts
      void batches.load()
    }
  } finally {
    running.value = false
  }
}

function openBatch(batchId: string): void {
  void router.push({ name: 'import-batch', params: { batchId } })
}

onMounted(() => {
  void loadKinds()
  void batches.load()
})
</script>

<template>
  <PageSection
    title="批量导入"
    description="上传首批资源的 CSV：先预检逐行报错，全部通过才能导入；同一文件重复导入不会重复写。按顺序导：先经营主体，再收款账户，再商品。"
    traced
  >
    <el-alert type="info" :closable="false" class="block">
      <template #title>
        模板在仓库 <code>infra/import-templates/</code>（只有表头），填表规则见 <code>docs/ops/首批资源导入.md</code>：
        表头不要改，另存为“CSV UTF-8”，金额填分，时间填北京时间，状态先填草稿或停用。
      </template>
    </el-alert>

    <el-alert v-if="kindsError" type="error" :title="kindsError" :closable="false" class="block">
      <el-button size="small" @click="loadKinds">重新加载</el-button>
    </el-alert>

    <div class="kinds" data-testid="import-kinds">
      <button
        v-for="spec in kinds"
        :key="spec.kind"
        type="button"
        :class="['kind', { 'kind--on': spec.kind === kind }]"
        @click="choose(spec.kind)"
      >
        <span class="kind__title">
          {{ spec.title || KIND_LABELS[spec.kind] }}
          <el-tag v-if="!spec.writable" size="small" type="warning">只能预检</el-tag>
        </span>
        <span class="kind__files">{{ spec.files.map((file) => file.fileName).join('、') }}</span>
      </button>
    </div>

    <template v-if="chosen">
      <h3 class="title">{{ chosen.title || KIND_LABELS[chosen.kind] }}：选文件</h3>
      <el-table :data="chosen.files" row-key="fileName" border size="small" class="block">
        <el-table-column prop="fileName" label="模板文件" min-width="220" />
        <el-table-column prop="title" label="内容" min-width="200" />
        <el-table-column label="必须带" width="80">
          <template #default="{ row }">{{ row.required ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="表头" min-width="320">
          <template #default="{ row }"><code class="columns">{{ row.columns.join(',') }}</code></template>
        </el-table-column>
      </el-table>
      <el-upload v-model:file-list="fileList" :auto-upload="false" multiple accept=".csv,text/csv" drag
                 data-testid="import-upload">
        <div>把 CSV 拖到这里，或<em>点击选择</em></div>
        <template #tip>
          <div class="sub">文件名用模板名（可以加序号，如 03-mall-products-2.csv）；一次最多 12 个，每个 ≤2 MB。</div>
        </template>
      </el-upload>
      <div class="actions">
        <el-button type="primary" :loading="checking" :disabled="fileList.length === 0" data-testid="import-check"
                   @click="check">
          预检
        </el-button>
      </div>
    </template>

    <template v-if="result">
      <h3 class="title">预检结果</h3>
      <ImportBatchResult
        :batch="result"
        :runnable="canRun(result, resultWritable)"
        :running="running"
        :check-only="!resultWritable"
        @run="run"
      />
    </template>

    <h3 class="title">导入记录</h3>
    <el-form inline @submit.prevent="batches.search()">
      <el-form-item label="类别">
        <el-select v-model="batches.filters.kind" clearable placeholder="全部" style="width: 180px"
                   @change="batches.search()">
          <el-option v-for="value in KIND_ORDER" :key="value" :label="KIND_LABELS[value]" :value="value" />
        </el-select>
      </el-form-item>
    </el-form>
    <PagedTable
      :items="batches.items.value"
      :total="batches.total.value"
      :page="batches.page.value"
      :size="batches.size.value"
      :loading="batches.loading.value"
      :error="batches.error.value"
      row-key="batchId"
      empty-text="还没有导入记录"
      @change-page="batches.changePage"
      @change-size="batches.changeSize"
      @retry="batches.load()"
    >
      <el-table-column label="预检时间" width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="openBatch((row as OpsImportBatchSummary).batchId)">
            {{ formatDateTime((row as OpsImportBatchSummary).checkedAt) }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="类别" width="140">
        <template #default="{ row }">{{ KIND_LABELS[(row as OpsImportBatchSummary).kind] }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS_VIEW[(row as OpsImportBatchSummary).status].tag">
            {{ STATUS_VIEW[(row as OpsImportBatchSummary).status].label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="文件" min-width="220">
        <template #default="{ row }">{{ (row as OpsImportBatchSummary).fileNames.join('、') }}</template>
      </el-table-column>
      <el-table-column label="行 / 问题 / 改动" width="140">
        <template #default="{ row }">
          {{ (row as OpsImportBatchSummary).rowCount }} / {{ (row as OpsImportBatchSummary).issueCount }} /
          {{ (row as OpsImportBatchSummary).changeCount }}
        </template>
      </el-table-column>
      <el-table-column label="预检人" width="120" prop="checkedByName" />
      <el-table-column label="导入" min-width="180">
        <template #default="{ row }">
          <span v-if="(row as OpsImportBatchSummary).importedAt">
            {{ (row as OpsImportBatchSummary).importedByName }} · {{ formatDateTime((row as OpsImportBatchSummary).importedAt) }}
          </span>
          <span v-else class="sub">—</span>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <CreatedAccountsDialog :accounts="createdAccounts" @close="createdAccounts = []" />
</template>

<style scoped>
.block {
  margin-bottom: 12px;
}

.kinds {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 10px;
}

.kind {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 10px;
  background: #fff;
  text-align: left;
  cursor: pointer;
  font: inherit;
}

.kind--on {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.kind__title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.kind__files {
  font-size: 12px;
  color: var(--admin-text-secondary);
  word-break: break-all;
}

.title {
  margin: 24px 0 10px;
  font-size: 16px;
}

.columns {
  font-size: 12px;
  word-break: break-all;
}

.actions {
  margin-top: 12px;
}

.sub {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
