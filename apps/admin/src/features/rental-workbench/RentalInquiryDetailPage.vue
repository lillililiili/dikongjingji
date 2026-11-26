<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import ReasonDialog from './ReasonDialog.vue'
import RentalQuoteDialog from './RentalQuoteDialog.vue'
import {
  canDecline,
  canQuote,
  CLOSE_REASON_LABELS,
  createRentalWorkbenchApi,
  HANDOVER_MODE_LABELS,
  INQUIRY_STATUS,
  lesseeLabel,
  ORDER_STATUS,
  placeLabel,
  REFERENCE_UNIT_LABELS,
  RETURN_MODE_LABELS,
  type RentalInquiry,
} from './rental-api'

const route = useRoute()
const router = useRouter()
const api = createRentalWorkbenchApi(http)
const inquiry = shallowRef<RentalInquiry | null>(null)
const loading = ref(false)
const error = ref('')
const quoting = shallowRef<RentalInquiry | null>(null)
const declining = ref(false)
const busy = ref(false)

const inquiryId = computed(() => String(route.params.inquiryId ?? ''))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    inquiry.value = await api.inquiry(inquiryId.value)
  } catch (failure) {
    inquiry.value = null
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function quoted(next: RentalInquiry): void {
  inquiry.value = next
  quoting.value = null
}

async function decline(reason: string): Promise<void> {
  const current = inquiry.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({
    title: '确认拒绝这份询价',
    message: '拒绝后客户在 App 里看到原因，这份询价不能再报价。',
    confirmText: '拒绝询价',
    danger: true,
  })
  if (!confirmed) return
  busy.value = true
  try {
    inquiry.value = await api.decline(current.inquiryId, reason)
    declining.value = false
    ElMessage.success('已拒绝这份询价，客户会看到原因')
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
    :title="inquiry ? `${inquiry.item.productName} 的租赁询价` : '租赁询价'"
    :description="inquiry ? `提交于 ${formatDateTime(inquiry.submittedAt)}，报价截止 ${formatDateTime(inquiry.quoteDeadline)}` : undefined"
  >
    <template #actions>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-button @click="router.push({ name: 'workbench-rental-inquiries' })">返回列表</el-button>
      <template v-if="inquiry">
        <el-button v-if="canDecline(inquiry)" type="danger" plain @click="declining = true">拒绝</el-button>
        <el-button v-if="canQuote(inquiry)" type="primary" @click="quoting = inquiry">
          {{ inquiry.quote ? '改价' : '报价' }}
        </el-button>
      </template>
    </template>

    <div v-loading="loading">
      <el-result v-if="error && !inquiry" icon="error" :title="error">
        <template #extra><el-button type="primary" @click="load">重新加载</el-button></template>
      </el-result>

      <template v-if="inquiry">
        <div class="status">
          <el-tag :type="INQUIRY_STATUS[inquiry.status].tag" size="large">{{ INQUIRY_STATUS[inquiry.status].label }}</el-tag>
          <span v-if="inquiry.declineReason">拒绝原因：{{ inquiry.declineReason }}</span>
          <span v-if="inquiry.closeReason">{{ CLOSE_REASON_LABELS[inquiry.closeReason] }}</span>
          <el-button v-if="inquiry.order" link type="primary"
                     @click="router.push({ name: 'workbench-rental-order', params: { orderId: inquiry.order.rentalOrderId } })">
            查看租赁订单（{{ ORDER_STATUS[inquiry.order.status].label }}）
          </el-button>
        </div>

        <h3 class="title">询价内容</h3>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="设备">{{ inquiry.item.productName }} · {{ inquiry.item.skuName }}</el-descriptions-item>
          <el-descriptions-item label="商品页参考价">
            <template v-if="inquiry.item.referenceAmountMinor === null">未标价</template>
            <template v-else>
              {{ formatMoney(inquiry.item.referenceAmountMinor) }}{{ REFERENCE_UNIT_LABELS[inquiry.item.referenceUnit] }} 起
            </template>
          </el-descriptions-item>
          <el-descriptions-item label="承租方">{{ lesseeLabel(inquiry.lessee) }}</el-descriptions-item>
          <el-descriptions-item label="租期">
            {{ inquiry.startDate }} 至 {{ inquiry.endDate }}，共 {{ inquiry.days }} 天，{{ inquiry.quantity }} 台
          </el-descriptions-item>
          <el-descriptions-item label="交接方式">{{ HANDOVER_MODE_LABELS[inquiry.handoverMode] }}</el-descriptions-item>
          <el-descriptions-item label="归还方式">{{ RETURN_MODE_LABELS[inquiry.returnMode] }}</el-descriptions-item>
          <el-descriptions-item label="使用地点" :span="2">
            {{ placeLabel(inquiry.place) }}（{{ inquiry.place.recipientName }} {{ inquiry.place.phone }}）
            <span class="muted">客户付款之前手机号是打码的。</span>
          </el-descriptions-item>
          <el-descriptions-item label="客户补充" :span="2">
            <span class="pre">{{ inquiry.note || '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>

        <h3 class="title">最新报价</h3>
        <el-empty v-if="!inquiry.quote" description="还没有报价" :image-size="60" />
        <template v-else>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="版本">第 {{ inquiry.quote.version }} 版，{{ formatDateTime(inquiry.quote.quotedAt) }}</el-descriptions-item>
            <el-descriptions-item label="有效期至">
              {{ formatDateTime(inquiry.quote.validUntil) }}
              <el-tag v-if="inquiry.quote.expired" type="info" size="small">已过期</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="租金">
              {{ formatMoney(inquiry.quote.dailyRentMinor) }}/天 × {{ inquiry.quote.days }} 天 × {{ inquiry.quote.quantity }} 台
              = {{ formatMoney(inquiry.quote.rentMinor) }}
            </el-descriptions-item>
            <el-descriptions-item label="档期">{{ inquiry.quote.scheduleAvailable ? '可以出租' : '档期不可用' }}</el-descriptions-item>
            <el-descriptions-item label="其他费用" :span="2">
              <div v-if="inquiry.quote.fees.length === 0">无</div>
              <div v-for="fee in inquiry.quote.fees" :key="fee.name">
                {{ fee.name }} {{ formatMoney(fee.amountMinor) }}<span v-if="fee.note" class="muted">（{{ fee.note }}）</span>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="合计" :span="2">
              <strong class="total">{{ formatMoney(inquiry.quote.totalMinor) }}</strong>
            </el-descriptions-item>
            <el-descriptions-item label="合同条款" :span="2"><span class="pre">{{ inquiry.quote.terms }}</span></el-descriptions-item>
          </el-descriptions>
        </template>
      </template>
    </div>
  </PageSection>

  <RentalQuoteDialog :api="api" :inquiry="quoting" @changed="quoted" @close="quoting = null" />
  <ReasonDialog
    :open="declining"
    title="拒绝询价"
    hint="写一句原因，客户在 App 里能看到。"
    placeholder="比如：这段时间设备已租出"
    confirm-text="拒绝询价"
    :busy="busy"
    @submit="decline"
    @close="declining = false"
  />
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

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.pre {
  white-space: pre-wrap;
}

.total {
  font-size: 16px;
  color: var(--admin-brand-text);
}
</style>
