<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { formatDate, formatDateTime, formatMoney } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import DeclineDialog from './DeclineDialog.vue'
import {
  canDecline,
  createMallInquiryApi,
  inquiryStatusLabel,
  INVITATION_STATUS_LABELS,
  QUOTE_STATUS_LABELS,
  quoteAction,
  type MallInquiryApi,
  type MallMerchantInquiry,
} from './mall-inquiry-api'
import QuoteDialog from './QuoteDialog.vue'

const props = defineProps<{
  api?: MallInquiryApi
}>()

const api = props.api ?? createMallInquiryApi(http)
const route = useRoute()
const router = useRouter()
const inquiry = shallowRef<MallMerchantInquiry | null>(null)
const loading = ref(false)
const error = ref('')
const dialog = ref<'quote' | 'decline' | null>(null)

const inquiryId = computed(() => String(route.params.inquiryId ?? ''))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    inquiry.value = await api.detail(inquiryId.value)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function changed(next: MallMerchantInquiry): void {
  dialog.value = null
  inquiry.value = next
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="inquiry ? `询价：${inquiry.product.name}` : '询价单'"
    :description="inquiry ? `${inquiryStatusLabel(inquiry.status)} · 本机构${INVITATION_STATUS_LABELS[inquiry.invitationStatus]}` : undefined"
  >
    <template #actions>
      <template v-if="inquiry">
        <el-button v-if="quoteAction(inquiry)" type="primary" @click="dialog = 'quote'">{{ quoteAction(inquiry) }}</el-button>
        <el-button v-if="canDecline(inquiry)" type="danger" plain @click="dialog = 'decline'">拒绝</el-button>
      </template>
      <el-button @click="load()">刷新</el-button>
      <el-button @click="router.push({ name: 'workbench-mall-inquiries' })">返回列表</el-button>
    </template>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load()">重新加载</el-button>
    </el-alert>
    <div v-else-if="loading && !inquiry" v-loading="true" class="placeholder" />
    <template v-else-if="inquiry">
      <h2 class="block-title">客户的需求</h2>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="商品">{{ inquiry.product.name }}</el-descriptions-item>
        <el-descriptions-item label="数量">{{ inquiry.quantity }} 件</el-descriptions-item>
        <el-descriptions-item label="期望交付">{{ inquiry.expectedDate ? formatDate(`${inquiry.expectedDate}T12:00:00+08:00`) : '没有要求' }}</el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ formatDateTime(inquiry.submittedAt) }}</el-descriptions-item>
        <el-descriptions-item label="报价截止">{{ formatDateTime(inquiry.quoteDeadline) }}</el-descriptions-item>
        <el-descriptions-item label="现在能报价">{{ inquiry.quotingOpen ? '能' : '不能' }}</el-descriptions-item>
        <el-descriptions-item label="配置和用途" :span="2">
          <span class="text">{{ inquiry.requirement }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <p class="hint">客户的身份和联系方式不给商家；有问题可以在“客服会话”里沟通。</p>

      <h2 class="block-title">本机构的报价</h2>
      <el-alert v-if="inquiry.invitationStatus === 'DECLINED'" type="info" :closable="false"
                :title="`已拒绝：${inquiry.declineReason ?? ''}`" />
      <el-descriptions v-if="inquiry.myQuote" :column="2" border>
        <el-descriptions-item label="状态">{{ QUOTE_STATUS_LABELS[inquiry.myQuote.status] }}</el-descriptions-item>
        <el-descriptions-item label="第几版">第 {{ inquiry.myQuote.version }} 版</el-descriptions-item>
        <el-descriptions-item label="单价">{{ formatMoney(inquiry.myQuote.unitAmountMinor) }}</el-descriptions-item>
        <el-descriptions-item label="合计">
          {{ formatMoney(inquiry.myQuote.totalMinor) }}（{{ inquiry.myQuote.quantity }} 件）
        </el-descriptions-item>
        <el-descriptions-item label="型号配置">{{ inquiry.myQuote.model }}</el-descriptions-item>
        <el-descriptions-item label="交期">{{ inquiry.myQuote.leadDays }} 天</el-descriptions-item>
        <el-descriptions-item label="包含内容" :span="2">{{ inquiry.myQuote.includes ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="报价时间">{{ formatDateTime(inquiry.myQuote.quotedAt) }}</el-descriptions-item>
        <el-descriptions-item label="有效期到">{{ formatDateTime(inquiry.myQuote.validUntil) }}</el-descriptions-item>
      </el-descriptions>
      <el-empty v-else-if="inquiry.invitationStatus !== 'DECLINED'" description="还没有报价" :image-size="60" />
      <p v-if="inquiry.myQuote?.status === 'CHOSEN'" class="hint">
        客户选中了你们的报价，会按这份报价下单付款，订单出现在“商城订单”里。
      </p>
    </template>
  </PageSection>

  <template v-if="inquiry">
    <QuoteDialog :api="api" :inquiry="dialog === 'quote' ? inquiry : null" @close="dialog = null" @done="changed" />
    <DeclineDialog :api="api" :inquiry="dialog === 'decline' ? inquiry : null" @close="dialog = null" @done="changed" />
  </template>
</template>

<style scoped>
.block-title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.block-title:first-child {
  margin-top: 0;
}

.placeholder {
  min-height: 200px;
}

.text {
  white-space: pre-wrap;
}

.hint {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--admin-text-secondary);
}
</style>
