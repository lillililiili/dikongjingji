<script setup lang="ts">
import { computed } from 'vue'

import { formatDateTime } from '@/core/ui/format'

import {
  CHANGE_LABELS,
  IMPORT_LIMITS,
  KIND_LABELS,
  REF_KIND_LABELS,
  STATUS_VIEW,
  type OpsImportBatch,
} from './import-api'

/** 一个批次的预检 / 导入结果：状态、文件、问题、核对列、改动。导入按钮由父组件决定能不能点。 */
const props = defineProps<{
  batch: OpsImportBatch
  runnable: boolean
  running: boolean
  /** 这一类这一版只能预检。 */
  checkOnly: boolean
}>()

const emit = defineEmits<{
  run: []
}>()

const status = computed(() => STATUS_VIEW[props.batch.status])
const truncated = computed(() => props.batch.issueCount > props.batch.issues.length
  || props.batch.issueCount > IMPORT_LIMITS.listedIssues)
</script>

<template>
  <div class="result" data-testid="import-result">
    <div class="result__head">
      <el-tag :type="status.tag" size="large" data-testid="import-status">{{ status.label }}</el-tag>
      <span>{{ KIND_LABELS[batch.kind] }} · {{ batch.rowCount }} 行 · 问题 {{ batch.issueCount }} 条</span>
      <span class="sub">批次号 {{ batch.batchId }}</span>
      <span class="sub">预检：{{ batch.checkedByName }} {{ formatDateTime(batch.checkedAt) }}</span>
      <span v-if="batch.importedAt" class="sub">导入：{{ batch.importedByName }} {{ formatDateTime(batch.importedAt) }}</span>
      <span class="sub">文件保留到 {{ formatDateTime(batch.retainedUntil) }}</span>
      <el-button v-if="runnable" type="primary" :loading="running" data-testid="import-run" @click="emit('run')">
        导入
      </el-button>
      <slot name="actions" />
    </div>

    <el-alert v-if="batch.duplicateOfBatchId" type="info" :closable="false" class="block"
              data-testid="import-duplicate">
      <template #title>
        和以前导入过的批次内容相同，不会重复写入。
        <router-link :to="{ name: 'import-batch', params: { batchId: batch.duplicateOfBatchId } }">
          看第一次导入的批次
        </router-link>
      </template>
    </el-alert>
    <el-alert v-if="batch.status === 'CHECKED' && checkOnly" type="warning" :closable="false" class="block"
              title="预检通过。这一类这一版只能预检，还不能导入；文件先留好，等开放写入后再导。" />
    <el-alert v-if="batch.status === 'REJECTED'" type="error" :closable="false" class="block"
              title="有问题，不能导入。按下面的列表改好表格后重新预检。" />

    <h3 class="title">文件</h3>
    <el-table :data="batch.files" row-key="fileName" border size="small">
      <el-table-column prop="fileName" label="文件" min-width="240" />
      <el-table-column prop="rowCount" label="数据行数" width="100" />
    </el-table>

    <template v-if="batch.issues.length > 0">
      <h3 class="title">问题（{{ batch.issueCount }} 条）</h3>
      <p v-if="truncated" class="sub">只列出前 {{ batch.issues.length }} 条，改好这些后重新预检会看到剩下的。</p>
      <el-table :data="batch.issues" border size="small" data-testid="import-issues">
        <el-table-column prop="fileName" label="文件" min-width="200" />
        <el-table-column label="行" width="70">
          <template #default="{ row }">{{ row.line === 0 ? '整个文件' : row.line }}</template>
        </el-table-column>
        <el-table-column label="列" width="170">
          <template #default="{ row }">{{ row.column ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="说明" min-width="260" />
      </el-table>
    </template>

    <template v-if="batch.reviewItems.length > 0">
      <h3 class="title">核对列</h3>
      <p class="sub">只在这里显示、不存库，请和线下材料（营业执照等）核对。</p>
      <el-table :data="batch.reviewItems" border size="small" data-testid="import-review">
        <el-table-column prop="fileName" label="文件" min-width="200" />
        <el-table-column prop="line" label="行" width="70" />
        <el-table-column prop="reference" label="编号" min-width="160" />
        <el-table-column prop="label" label="项目" width="160" />
        <el-table-column prop="value" label="填的内容" min-width="200" />
      </el-table>
    </template>

    <template v-if="batch.changes.length > 0">
      <h3 class="title">改动（{{ batch.changes.length }} 项）</h3>
      <el-table :data="batch.changes" border size="small" data-testid="import-changes">
        <el-table-column label="对象" width="120">
          <template #default="{ row }">{{ REF_KIND_LABELS[row.refKind as keyof typeof REF_KIND_LABELS] }}</template>
        </el-table-column>
        <el-table-column prop="reference" label="编号" min-width="200" />
        <el-table-column label="改动" width="90">
          <template #default="{ row }">
            <el-tag :type="row.change === 'CREATED' ? 'success' : 'info'" size="small">
              {{ CHANGE_LABELS[row.change as keyof typeof CHANGE_LABELS] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="targetId" label="系统 ID" min-width="300" />
      </el-table>
    </template>
  </div>
</template>

<style scoped>
.result__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.block {
  margin-bottom: 12px;
}

.title {
  margin: 20px 0 8px;
  font-size: 15px;
}

.sub {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
