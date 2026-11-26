<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'

import { http } from '@/core/http'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import {
  createReconciliationApi,
  csvFileName,
  defaultReconciliationDate,
  DIFFERENCE_LABELS,
  isSelectableDate,
  saveBlob,
  type Reconciliation,
  type ReconciliationApi,
  type ReconciliationDifference,
  type ReconciliationRun,
} from './reconciliation-api'

/**
 * 对账下载（平台运营）：选一天（北京时间，今天以前），看这一天微信账单和平台支付、退款逐笔核对的结果，下载差异表 CSV；
 * 账单还没全部导入时可以立刻补导入一次。差异只记录，不自动改订单。
 */
const props = defineProps<{
  /** 测试时换成替身；页面里用全站的接口客户端。 */
  api?: ReconciliationApi
  /** 测试时换成替身；默认交给浏览器保存。 */
  save?: (blob: Blob, fileName: string) => void
}>()
const api = props.api ?? createReconciliationApi(http)
const save = props.save ?? saveBlob
const date = ref(defaultReconciliationDate())
const result = shallowRef<Reconciliation | null>(null)
const loading = ref(false)
const error = ref<{ notReady: boolean, message: string } | null>(null)
const downloading = ref(false)
const running = ref(false)
const redo = ref(false)
const lastRun = shallowRef<ReconciliationRun | null>(null)
let revision = 0

const canQuery = computed(() => isSelectableDate(date.value))

function disabledDate(day: Date): boolean {
  const text = `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`
  return !isSelectableDate(text)
}

async function load(): Promise<void> {
  if (!canQuery.value) return
  const mine = ++revision
  loading.value = true
  error.value = null
  try {
    const loaded = await api.read(date.value)
    if (mine !== revision) return
    result.value = loaded
  } catch (failure) {
    if (mine !== revision) return
    result.value = null
    error.value = {
      notReady: failure instanceof ApiRequestError && failure.code === 'TRADE_RECONCILIATION_NOT_READY',
      message: errorMessage(failure),
    }
  } finally {
    if (mine === revision) loading.value = false
  }
}

function changeDate(value: string | null): void {
  date.value = value ?? ''
  revision++
  loading.value = false
  result.value = null
  error.value = null
  lastRun.value = null
  void load()
}

async function download(): Promise<void> {
  if (!canQuery.value || downloading.value) return
  const selectedDate = date.value
  downloading.value = true
  try {
    const file = await api.downloadCsv(selectedDate)
    save(file.blob, csvFileName(selectedDate, file.fileName))
  } catch (failure) {
    showError(failure)
  } finally {
    downloading.value = false
  }
}

async function run(): Promise<void> {
  if (!canQuery.value || running.value) return
  const selectedDate = date.value
  const redownload = redo.value
  running.value = true
  try {
    const confirmed = await confirmAction({
      title: '重新对账',
      message: redownload
        ? `向微信重新下载 ${selectedDate} 所有子商户的账单并覆盖，再逐笔核对。差异只记录，不会改订单。`
        : `补导入 ${selectedDate} 还没导入成功的子商户账单（已导入的跳过），再逐笔核对。差异只记录，不会改订单。`,
      confirmText: '开始对账',
    })
    if (!confirmed) return
    const summary = await api.run(selectedDate, redownload)
    if (selectedDate === date.value) {
      lastRun.value = summary
      await load()
    }
  } catch (failure) {
    showError(failure)
  } finally {
    running.value = false
  }
}

function amount(value: number | null): string {
  return value === null ? '—' : formatMoney(value)
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    title="对账下载"
    description="按北京时间选一天：微信的 T 日账单 T+1 日 10 点后才有，平台按子商户逐个下载，和平台的支付单、退款单逐笔核对。差异只记录，不自动改订单。"
  >
    <div class="reconciliation">
      <div class="reconciliation__toolbar">
        <el-date-picker
          :model-value="date"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          :clearable="false"
          :disabled-date="disabledDate"
          style="width: 160px"
          @update:model-value="changeDate"
        />
        <el-button :disabled="!canQuery" @click="load">刷新</el-button>
        <el-button type="primary" :disabled="!canQuery" :loading="downloading" @click="download">下载差异表（CSV）</el-button>
        <el-divider direction="vertical" />
        <el-checkbox v-model="redo">已导入的子商户也重新下载</el-checkbox>
        <el-button :disabled="!canQuery" :loading="running" @click="run">重新对账</el-button>
      </div>
      <span class="muted">今天的账单还没有，只能选今天以前的日期。</span>

      <el-alert
        v-if="lastRun"
        :type="lastRun.failed > 0 ? 'warning' : 'success'"
        :closable="false"
        show-icon
        :title="`${lastRun.date} 这次对账：要对账的子商户 ${lastRun.merchants} 家，导入成功 ${lastRun.imported}、失败 ${lastRun.failed}、跳过（之前已导入）${lastRun.skipped}，发现差异 ${lastRun.differences} 条。`"
        :description="lastRun.failed > 0 ? '失败的子商户可能是账单还在生成、渠道不通或文件校验不过，稍后再点“重新对账”。' : ''"
      />

      <div v-loading="loading" class="reconciliation__body">
        <el-result
          v-if="error"
          :icon="error.notReady ? 'warning' : 'error'"
          :title="error.notReady ? '这一天的账单还没全部导入' : '没读到对账结果'"
          :sub-title="error.message"
        >
          <template #extra>
            <el-button v-if="error.notReady" type="primary" :loading="running" @click="run">重新对账</el-button>
            <el-button @click="load">重新加载</el-button>
          </template>
        </el-result>

        <el-empty v-else-if="result && result.importedAt === null" description="当天没有需要对账的商户或交易" />
        <template v-else-if="result">
          <el-descriptions :column="4" border>
            <el-descriptions-item label="日期">{{ result.date }}</el-descriptions-item>
            <el-descriptions-item label="账单导入时间">{{ formatDateTime(result.importedAt) }}</el-descriptions-item>
            <el-descriptions-item label="核对一致">{{ result.matchedCount }} 笔</el-descriptions-item>
            <el-descriptions-item label="差异">
              <el-tag :type="result.differences.length > 0 ? 'danger' : 'success'" effect="plain">
                {{ result.differences.length }} 条
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>

          <el-table :data="result.differences" empty-text="这一天没有差异" class="reconciliation__table">
            <el-table-column label="类型" width="170">
              <template #default="{ row }">{{ DIFFERENCE_LABELS[(row as ReconciliationDifference).kind] }}</template>
            </el-table-column>
            <el-table-column label="子商户号" prop="subMchid" width="150" />
            <el-table-column label="商户订单号" min-width="200">
              <template #default="{ row }">{{ (row as ReconciliationDifference).outTradeNo ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="商户退款单号" min-width="200">
              <template #default="{ row }">{{ (row as ReconciliationDifference).outRefundNo ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="平台金额" width="120" align="right">
              <template #default="{ row }">{{ amount((row as ReconciliationDifference).platformAmountMinor) }}</template>
            </el-table-column>
            <el-table-column label="微信金额" width="120" align="right">
              <template #default="{ row }">{{ amount((row as ReconciliationDifference).channelAmountMinor) }}</template>
            </el-table-column>
          </el-table>
        </template>

        <el-empty v-else-if="!loading" description="选一个今天以前的日期查看对账结果" />
      </div>
    </div>
  </PageSection>
</template>

<style scoped>
.reconciliation {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.reconciliation__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.reconciliation__toolbar .el-button + .el-button {
  margin-left: 0;
}

.reconciliation__body {
  min-height: 160px;
}

.reconciliation__table {
  margin-top: 12px;
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
