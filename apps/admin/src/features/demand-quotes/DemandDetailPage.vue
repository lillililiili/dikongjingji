<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import DemandQuoteDialog from './DemandQuoteDialog.vue'
import {
  budgetLabel,
  canQuote,
  canWithdraw,
  createDemandQuotesApi,
  DEMAND_STATUS,
  DEMAND_TYPE_LABELS,
  QUOTE_STATUS,
  type ProviderDemand,
} from './demand-quotes-api'
import { extrasRows, lineQuantityLabel, type QuoteLine } from './quote-lines'

const route = useRoute()
const router = useRouter()
const api = createDemandQuotesApi(http)
const demand = shallowRef<ProviderDemand | null>(null)
const loading = ref(false)
const error = ref('')
const quoting = shallowRef<ProviderDemand | null>(null)
const busy = ref(false)

const demandId = computed(() => String(route.params.demandId ?? ''))
const myExtras = computed(() => (demand.value?.myQuote ? extrasRows(demand.value.myQuote) : []))
const quoteLabel = computed(() => (canWithdraw(demand.value ?? { myQuote: null }) ? '改价' : '报价'))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    demand.value = await api.detail(demandId.value)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function quoted(next: ProviderDemand): void {
  demand.value = next
  quoting.value = null
}

async function withdraw(): Promise<void> {
  const current = demand.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({
    title: '确认撤回报价',
    message: '撤回后发布人看不到这份报价；报价期内还可以重新报价。',
    confirmText: '撤回报价',
    danger: true,
  })
  if (!confirmed) return
  busy.value = true
  try {
    demand.value = await api.withdraw(current.demandId)
    ElMessage.success('已撤回报价')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="demand?.title ?? '需求'"
    :description="demand ? `${DEMAND_TYPE_LABELS[demand.type]} · ${demand.cityName} · 提交于 ${formatDateTime(demand.submittedAt)}` : undefined"
  >
    <template #actions>
      <el-button @click="router.push({ name: 'workbench-demands' })">返回列表</el-button>
      <template v-if="demand">
        <el-button v-if="canWithdraw(demand)" type="danger" plain :loading="busy" @click="withdraw">撤回报价</el-button>
        <el-button v-if="canQuote(demand)" type="primary" @click="quoting = demand">{{ quoteLabel }}</el-button>
      </template>
    </template>

    <div v-loading="loading">
      <el-result v-if="error && !demand" icon="error" :title="error">
        <template #extra><el-button type="primary" @click="load">重新加载</el-button></template>
      </el-result>

      <template v-if="demand">
        <div class="status">
          <el-tag :type="DEMAND_STATUS[demand.status].tag" size="large">{{ DEMAND_STATUS[demand.status].label }}</el-tag>
          <span>{{ demand.invited ? '发布人邀请了本机构' : '平台按服务城市匹配' }}</span>
          <span>报价截止 {{ formatDateTime(demand.quoteDeadline) }}{{ demand.quotingOpen ? '' : '（已截止）' }}</span>
        </div>

        <h3 class="title">需求内容</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="地点" :span="2">{{ demand.cityName }}{{ demand.address }}</el-descriptions-item>
          <el-descriptions-item label="范围" :span="2">{{ demand.scope }}</el-descriptions-item>
          <el-descriptions-item label="预算">{{ budgetLabel(demand) }}</el-descriptions-item>
          <el-descriptions-item label="期望完成">{{ demand.dueDate ?? '不限' }}</el-descriptions-item>
          <el-descriptions-item label="要的交付物" :span="2">
            <el-tag v-for="item in demand.deliverables" :key="item.code" class="deliverable" effect="plain">
              {{ item.label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="补充说明" :span="2"><span class="pre">{{ demand.note || '—' }}</span></el-descriptions-item>
        </el-descriptions>
        <p class="muted">
          需求里没有发布人的联系方式。航拍、测绘、设备维护、技术支持被选定后生成服务订单，到“服务订单”里出方案；
          巡检需求选定后的方案和付款暂不在线上进行。
        </p>

        <h3 class="title">本机构的报价</h3>
        <el-empty v-if="!demand.myQuote" description="还没有报价" :image-size="60" />
        <el-descriptions v-else :column="2" border>
          <el-descriptions-item label="状态">
            <el-tag :type="QUOTE_STATUS[demand.myQuote.status].tag">{{ QUOTE_STATUS[demand.myQuote.status].label }}</el-tag>
            <span class="muted">第 {{ demand.myQuote.version }} 版</span>
          </el-descriptions-item>
          <el-descriptions-item label="报价">{{ formatMoney(demand.myQuote.amountMinor) }}</el-descriptions-item>
          <el-descriptions-item label="做法">{{ demand.myQuote.method }}</el-descriptions-item>
          <el-descriptions-item label="排期">{{ demand.myQuote.schedule }}</el-descriptions-item>
          <el-descriptions-item label="工期">{{ demand.myQuote.durationDays }} 天</el-descriptions-item>
          <el-descriptions-item label="有效期至">{{ formatDateTime(demand.myQuote.validUntil) }}</el-descriptions-item>
          <el-descriptions-item label="交付物" :span="2">{{ demand.myQuote.deliverables }}</el-descriptions-item>
          <el-descriptions-item v-for="row in myExtras" :key="row.label" :label="row.label">
            {{ row.value }}
          </el-descriptions-item>
          <el-descriptions-item label="首次报价">{{ formatDateTime(demand.myQuote.submittedAt) }}</el-descriptions-item>
          <el-descriptions-item label="最近改动">{{ formatDateTime(demand.myQuote.revisedAt) }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="demand.myQuote && demand.myQuote.lines.length > 0">
          <h4 class="subtitle">报价明细</h4>
          <el-table :data="demand.myQuote.lines" size="small" border>
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column label="数量 × 单价" min-width="160">
              <template #default="{ row }">{{ lineQuantityLabel(row as QuoteLine) || '—' }}</template>
            </el-table-column>
            <el-table-column label="金额" width="140">
              <template #default="{ row }">{{ formatMoney(row.amountMinor) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </template>
    </div>
  </PageSection>

  <DemandQuoteDialog :api="api" :demand="quoting" @changed="quoted" @close="quoting = null" />
</template>

<style scoped>
.status {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--admin-text-secondary);
}

.title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.subtitle {
  margin: 14px 0 8px;
  font-size: 13px;
}

.deliverable {
  margin-right: 6px;
}

.muted {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.pre {
  white-space: pre-wrap;
}
</style>
